package com.linkedin.venice.pushmonitor;

import static com.linkedin.venice.VeniceConstants.TYPE_STREAM_HYBRID_STORE_QUOTA;
import static com.linkedin.venice.VeniceConstants.TYPE_STREAM_REPROCESSING_HYBRID_STORE_QUOTA;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.venice.client.store.transport.TransportClient;
import com.linkedin.venice.client.store.transport.TransportClientResponse;
import com.linkedin.venice.exceptions.ErrorType;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.routerapi.HybridStoreQuotaStatusResponse;
import com.linkedin.venice.utils.DaemonThreadFactory;
import com.linkedin.venice.utils.ExceptionUtils;
import com.linkedin.venice.utils.ObjectMapperFactory;
import com.linkedin.venice.utils.Utils;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * This push monitor is able to query hybrid store quota status from routers
 */
public class RouterBasedHybridStoreQuotaMonitor implements Closeable {
  private static final Logger LOGGER = LogManager.getLogger(RouterBasedHybridStoreQuotaMonitor.class);

  private static final int POLL_CYCLE_DELAY_MS = 10000;
  private static final long POLL_TIMEOUT_MS = 10000L;

  private final ExecutorService executor;

  private final HybridQuotaMonitorTask hybridQuotaMonitorTask;
  private HybridStoreQuotaStatus currentStatus = HybridStoreQuotaStatus.QUOTA_NOT_VIOLATED;

  public RouterBasedHybridStoreQuotaMonitor(
      TransportClient transportClient,
      String storeName,
      Version.PushType pushType,
      String topicName,
      TransportClientReinitProvider reinitProvider) {
    final String requestPath;
    if (Version.PushType.STREAM.equals(pushType)) {
      requestPath = buildStreamHybridStoreQuotaRequestPath(storeName);
    } else if (Version.PushType.STREAM_REPROCESSING.equals(pushType)) {
      final String versionTopic = Version.composeVersionTopicFromStreamReprocessingTopic(topicName);
      requestPath = buildStreamReprocessingHybridStoreQuotaRequestPath(versionTopic);
    } else {
      throw new VeniceException(
          "Only push types " + pushType.STREAM + " and " + pushType.STREAM_REPROCESSING
              + " can monitor hybrid store quota.");
    }
    executor = Executors.newSingleThreadExecutor(new DaemonThreadFactory("RouterBasedHybridQuotaMonitor"));
    hybridQuotaMonitorTask = new HybridQuotaMonitorTask(transportClient, storeName, requestPath, this, reinitProvider);
  }

  public void start() {
    executor.submit(hybridQuotaMonitorTask);
  }

  @Override
  public void close() {
    hybridQuotaMonitorTask.close();
  }

  protected HybridQuotaMonitorTask getHybridQuotaMonitorTask() {
    return hybridQuotaMonitorTask;
  }

  public void setCurrentStatus(HybridStoreQuotaStatus currentStatus) {
    this.currentStatus = currentStatus;
  }

  public HybridStoreQuotaStatus getCurrentStatus() {
    return this.currentStatus;
  }

  private static String buildStreamHybridStoreQuotaRequestPath(String storeName) {
    return TYPE_STREAM_HYBRID_STORE_QUOTA + "/" + storeName;
  }

  private static String buildStreamReprocessingHybridStoreQuotaRequestPath(String versionTopic) {
    return TYPE_STREAM_REPROCESSING_HYBRID_STORE_QUOTA + "/" + versionTopic;
  }

  protected static class HybridQuotaMonitorTask implements Runnable, Closeable {
    private ObjectMapper mapper = ObjectMapperFactory.getInstance();

    private final AtomicBoolean isRunning;
    private final String storeName;
    private TransportClient transportClient;

    private TransportClientReinitProvider reinitProvider;
    private final String requestPath;
    private final RouterBasedHybridStoreQuotaMonitor hybridStoreQuotaMonitorService;

    public HybridQuotaMonitorTask(
        TransportClient transportClient,
        String storeName,
        String requestPath,
        RouterBasedHybridStoreQuotaMonitor hybridStoreQuotaMonitorService,
        TransportClientReinitProvider reinitProvider) {
      this.transportClient = transportClient;
      this.storeName = storeName;
      this.requestPath = requestPath;
      this.hybridStoreQuotaMonitorService = hybridStoreQuotaMonitorService;
      this.isRunning = new AtomicBoolean(true);
      this.reinitProvider = reinitProvider;
    }

    protected void setMapper(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    protected void checkStatus() throws ExecutionException, InterruptedException, TimeoutException, IOException {
      // Get hybrid store quota status
      CompletableFuture<TransportClientResponse> responseFuture = transportClient.get(requestPath);
      TransportClientResponse response = responseFuture.get(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      if (response == null) {
        LOGGER.error("Router was not able to get hybrid quota status! Received null response!");
        return;
      }
      HybridStoreQuotaStatusResponse quotaStatusResponse =
          mapper.readValue(response.getBody(), HybridStoreQuotaStatusResponse.class);
      if (quotaStatusResponse.isError()) {
        if (quotaStatusResponse.getErrorType().equals(ErrorType.STORE_NOT_FOUND)) {
          LOGGER.warn("Store not found, reinitializing client! Error: {}", quotaStatusResponse.getError());
          // TODO: It'd make sense to call shutdown on the transport client, but it's a shared object so that's
          // a bit dangerous.
          transportClient = reinitProvider.apply();
        }
        LOGGER.error("Router was not able to get hybrid quota status: {}", quotaStatusResponse.getError());
        return;
      }
      hybridStoreQuotaMonitorService.setCurrentStatus(quotaStatusResponse.getQuotaStatus());
      switch (quotaStatusResponse.getQuotaStatus()) {
        case QUOTA_VIOLATED:
          LOGGER.info("Hybrid job failed with quota violation for store: {}", storeName);
          break;
        default:
          LOGGER.info("Current hybrid job state: {} for store: {}", quotaStatusResponse.getQuotaStatus(), storeName);
      }
    }

    @Override
    public void run() {
      LOGGER.info("Running {}", this.getClass().getSimpleName());
      while (isRunning.get()) {
        try {
          checkStatus();
          Utils.sleep(POLL_CYCLE_DELAY_MS);
        } catch (Exception e) {
          if (isRunning.get() && !ExceptionUtils.recursiveClassEquals(e, InterruptedException.class)) {
            // Only worth logging if we're actually supposed to be running.
            LOGGER.error("Error when polling push status from router for store version: {}", storeName, e);
          } else {
            break;
          }
        }
      }
    }

    @Override
    public void close() {
      isRunning.set(false);
    }
  }

  @FunctionalInterface
  public interface TransportClientReinitProvider {
    TransportClient apply();
  }
}
