package com.linkedin.davinci.kafka.consumer;

import static com.linkedin.venice.ConfigKeys.KAFKA_BOOTSTRAP_SERVERS;

import com.linkedin.davinci.config.VeniceServerConfig;
import com.linkedin.davinci.ingestion.consumption.ConsumedDataReceiver;
import com.linkedin.davinci.stats.AggKafkaConsumerServiceStats;
import com.linkedin.davinci.utils.IndexedHashMap;
import com.linkedin.davinci.utils.IndexedMap;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.meta.ReadOnlyStoreRepository;
import com.linkedin.venice.pubsub.PubSubConsumerAdapterContext;
import com.linkedin.venice.pubsub.PubSubConsumerAdapterFactory;
import com.linkedin.venice.pubsub.api.DefaultPubSubMessage;
import com.linkedin.venice.pubsub.api.PubSubConsumerAdapter;
import com.linkedin.venice.pubsub.api.PubSubMessageDeserializer;
import com.linkedin.venice.pubsub.api.PubSubTopic;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.utils.ExceptionUtils;
import com.linkedin.venice.utils.LatencyUtils;
import com.linkedin.venice.utils.RandomAccessDaemonThreadFactory;
import com.linkedin.venice.utils.RedundantExceptionFilter;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import com.linkedin.venice.utils.locks.AutoCloseableLock;
import io.tehuti.metrics.MetricsRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * {@link KafkaConsumerService} is used to manage a pool of consumption-related resources connected to a specific Kafka
 * cluster.
 *
 * The reasons to have this pool are:
 * 1. To reduce the unnecessary overhead of having one consumer per store-version, each of which includes the internal
 *    IO threads/connections to brokers and internal buffers;
 * 2. To reduce the GC overhead when there are a lot of store versions bootstrapping/ingesting at the same time;
 * 3. To have a predictable and configurable upper bound on the total amount of resources occupied by consumers become,
 *    no matter how many store-versions are being hosted in the same instance;
 *
 * The responsibilities of this class include:
 * 1. Setting up a fixed size pool of consumption unit, where each unit contains exactly one:
 *    a) {@link SharedKafkaConsumer}
 *    b) {@link ConsumptionTask}
 *    c) {@link ConsumerSubscriptionCleaner}
 * 2. Receive various calls to interrogate or mutate consumer state, and delegate them to the correct unit, by
 *    maintaining a mapping of which unit belongs to which version-topic and subscribed topic-partition. Notably,
 *    the {@link #startConsumptionIntoDataReceiver(PartitionReplicaIngestionContext, long, ConsumedDataReceiver)} function allows the
 *    caller to start funneling consumed data into a receiver (i.e. into another task).
 * 3. Provide a single abstract function that must be overridden by subclasses in order to implement a consumption
 *    load balancing strategy: {@link #pickConsumerForPartition(PubSubTopic, PubSubTopicPartition)}
 *
 * @see AggKafkaConsumerService which wraps one instance of this class per Kafka cluster.
 */
public abstract class KafkaConsumerService extends AbstractKafkaConsumerService {
  protected final String kafkaUrl;
  protected final String kafkaUrlForLogger;
  protected final ConsumerPoolType poolType;
  protected final AggKafkaConsumerServiceStats aggStats;
  protected final IndexedMap<SharedKafkaConsumer, ConsumptionTask> consumerToConsumptionTask;
  protected final Map<PubSubTopic, Map<PubSubTopicPartition, SharedKafkaConsumer>> versionTopicToTopicPartitionToConsumer =
      new VeniceConcurrentHashMap<>();

  /**
    * This read-only per consumer lock is for protecting the partition unsubscription and data receiver setting operations.
    * Using consumer intrinsic lock may cause race condition, refer https://github.com/linkedin/venice/pull/1308
   */
  protected final Map<SharedKafkaConsumer, ReentrantLock> consumerToLocks = new HashMap<>();

  private RandomAccessDaemonThreadFactory threadFactory;
  private final Logger LOGGER;
  private final ExecutorService consumerExecutor;
  private static final int SHUTDOWN_TIMEOUT_IN_SECOND = 1;
  // 4MB bitset size, 2 bitmaps for active and old bitset
  private static final RedundantExceptionFilter REDUNDANT_LOGGING_FILTER =
      new RedundantExceptionFilter(8 * 1024 * 1024 * 4, TimeUnit.MINUTES.toMillis(10));
  private final VeniceServerConfig serverConfig;
  protected final ConsumerPollTracker consumerPollTracker;

  /**
   * @param statsOverride injection of stats, for test purposes
   */
  protected KafkaConsumerService(
      final ConsumerPoolType poolType,
      final PubSubConsumerAdapterFactory pubSubConsumerAdapterFactory,
      final Properties consumerProperties,
      final long readCycleDelayMs,
      final int numOfConsumersPerKafkaCluster,
      final IngestionThrottler ingestionThrottler,
      final KafkaClusterBasedRecordThrottler kafkaClusterBasedRecordThrottler,
      final MetricsRepository metricsRepository,
      final String kafkaClusterAlias,
      final long sharedConsumerNonExistingTopicCleanupDelayMS,
      final StaleTopicChecker staleTopicChecker,
      final boolean liveConfigBasedKafkaThrottlingEnabled,
      final PubSubMessageDeserializer pubSubDeserializer,
      final Time time,
      final AggKafkaConsumerServiceStats statsOverride,
      final boolean isKafkaConsumerOffsetCollectionEnabled,
      final ReadOnlyStoreRepository metadataRepository,
      final boolean isUnregisterMetricForDeletedStoreEnabled,
      VeniceServerConfig serverConfig) {
    this.kafkaUrl = consumerProperties.getProperty(KAFKA_BOOTSTRAP_SERVERS);
    this.kafkaUrlForLogger = Utils.getSanitizedStringForLogger(kafkaUrl);
    this.LOGGER = LogManager.getLogger(
        KafkaConsumerService.class.getSimpleName() + " [" + kafkaUrlForLogger + "-" + poolType.getStatSuffix() + "]");
    this.poolType = poolType;
    this.serverConfig = serverConfig;

    // Initialize consumers and consumerExecutor
    String consumerNamePrefix = "venice-shared-consumer-for-" + kafkaUrl + '-' + poolType.getStatSuffix();
    threadFactory = new RandomAccessDaemonThreadFactory(consumerNamePrefix, serverConfig.getRegionName());
    consumerExecutor = Executors.newFixedThreadPool(numOfConsumersPerKafkaCluster, threadFactory);
    this.consumerToConsumptionTask = new IndexedHashMap<>(numOfConsumersPerKafkaCluster);
    this.aggStats = statsOverride != null
        ? statsOverride
        : createAggKafkaConsumerServiceStats(
            metricsRepository,
            kafkaClusterAlias,
            this::getMaxElapsedTimeMSSinceLastPollInConsumerPool,
            metadataRepository,
            isUnregisterMetricForDeletedStoreEnabled);

    VeniceProperties properties = new VeniceProperties(consumerProperties);
    PubSubConsumerAdapterContext.Builder contextBuilder =
        new PubSubConsumerAdapterContext.Builder().setVeniceProperties(properties)
            .setPubSubMessageDeserializer(pubSubDeserializer)
            .setIsOffsetCollectionEnabled(isKafkaConsumerOffsetCollectionEnabled)
            .setPubSubPositionTypeRegistry(serverConfig.getPubSubPositionTypeRegistry());
    this.consumerPollTracker = new ConsumerPollTracker(time);
    for (int i = 0; i < numOfConsumersPerKafkaCluster; ++i) {
      /**
       * We need to assign a unique client id across all the storage nodes, otherwise, they will fail into the same throttling bucket.
       */
      contextBuilder.setConsumerName(i + poolType.getStatSuffix());
      SharedKafkaConsumer pubSubConsumer = new SharedKafkaConsumer(
          pubSubConsumerAdapterFactory.create(contextBuilder.build()),
          aggStats,
          this::recordPartitionsPerConsumerSensor,
          this::handleUnsubscription);

      Supplier<Map<PubSubTopicPartition, List<DefaultPubSubMessage>>> pollFunction =
          liveConfigBasedKafkaThrottlingEnabled
              ? () -> kafkaClusterBasedRecordThrottler.poll(pubSubConsumer, kafkaUrl, readCycleDelayMs)
              : () -> pubSubConsumer.poll(readCycleDelayMs);
      final IntConsumer bandwidthThrottlerFunction =
          totalBytes -> ingestionThrottler.maybeThrottleBandwidth(totalBytes);
      final IntConsumer recordsThrottlerFunction = recordsCount -> {
        ingestionThrottler.maybeThrottleRecordRate(poolType, recordsCount);
      };

      final ConsumerSubscriptionCleaner cleaner = new ConsumerSubscriptionCleaner(
          sharedConsumerNonExistingTopicCleanupDelayMS,
          1000,
          staleTopicChecker,
          pubSubConsumer::getAssignment,
          aggStats::recordTotalDetectedDeletedTopicNum,
          pubSubConsumer::batchUnsubscribe,
          time);

      ConsumptionTask consumptionTask = new ConsumptionTask(
          consumerNamePrefix,
          i,
          readCycleDelayMs,
          pollFunction,
          bandwidthThrottlerFunction,
          recordsThrottlerFunction,
          this.aggStats,
          cleaner,
          consumerPollTracker);
      consumerToConsumptionTask.putByIndex(pubSubConsumer, consumptionTask, i);
      consumerToLocks.put(pubSubConsumer, new ReentrantLock());
    }

    LOGGER.info("KafkaConsumerService was initialized with {} consumers.", numOfConsumersPerKafkaCluster);
  }

  /** May be overridden to clean up state in sub-classes */
  void handleUnsubscription(
      SharedKafkaConsumer consumer,
      PubSubTopic versionTopic,
      PubSubTopicPartition topicPartition) {
  }

  @Override
  public SharedKafkaConsumer getConsumerAssignedToVersionTopicPartition(
      PubSubTopic versionTopic,
      PubSubTopicPartition topicPartition) {
    Map<PubSubTopicPartition, SharedKafkaConsumer> map = versionTopicToTopicPartitionToConsumer.get(versionTopic);
    if (map == null) {
      return null;
    }
    return map.get(topicPartition);
  }

  /**
   * This function assigns a consumer for the given {@link StoreIngestionTask} and returns the assigned consumer.
   *
   * Must be idempotent and thus return previously a assigned consumer (for the same params) if any exists.
   */
  @Override
  public SharedKafkaConsumer assignConsumerFor(PubSubTopic versionTopic, PubSubTopicPartition topicPartition) {
    Map<PubSubTopicPartition, SharedKafkaConsumer> topicPartitionToConsumerMap =
        versionTopicToTopicPartitionToConsumer.computeIfAbsent(versionTopic, k -> new VeniceConcurrentHashMap<>());
    return topicPartitionToConsumerMap
        .computeIfAbsent(topicPartition, k -> pickConsumerForPartition(versionTopic, topicPartition));
  }

  protected abstract SharedKafkaConsumer pickConsumerForPartition(
      PubSubTopic versionTopic,
      PubSubTopicPartition topicPartition);

  protected void removeTopicPartitionFromConsumptionTask(
      PubSubConsumerAdapter consumer,
      PubSubTopicPartition topicPartition) {
    consumerToConsumptionTask.get(consumer).removeDataReceiver(topicPartition);
  }

  /**
   * Stop all subscription associated with the given version topic.
   */
  @Override
  public void unsubscribeAll(PubSubTopic versionTopic) {
    versionTopicToTopicPartitionToConsumer.compute(versionTopic, (k, topicPartitionToConsumerMap) -> {
      if (topicPartitionToConsumerMap != null) {
        topicPartitionToConsumerMap.forEach((topicPartition, sharedConsumer) -> {
          /**
           * Refer {@link KafkaConsumerService#startConsumptionIntoDataReceiver} for avoiding race condition caused by
           * setting data receiver and unsubscribing concurrently for the same topic partition on a shared consumer.
           */
          try (AutoCloseableLock ignored = AutoCloseableLock.of(consumerToLocks.get(sharedConsumer))) {
            sharedConsumer.unSubscribe(topicPartition);
            removeTopicPartitionFromConsumptionTask(sharedConsumer, topicPartition);
          }
          consumerPollTracker.removeTopicPartition(topicPartition);
        });
      }
      return null;
    });
  }

  /**
   * Stop specific subscription associated with the given version topic.
   */
  @Override
  public void unSubscribe(PubSubTopic versionTopic, PubSubTopicPartition pubSubTopicPartition, long timeoutMs) {
    SharedKafkaConsumer consumer = getConsumerAssignedToVersionTopicPartition(versionTopic, pubSubTopicPartition);
    if (consumer != null) {
      /**
       * Refer {@link KafkaConsumerService#startConsumptionIntoDataReceiver} for avoiding race condition caused by
       * setting data receiver and unsubscribing concurrently for the same topic partition on a shared consumer.
       */
      try (AutoCloseableLock ignored = AutoCloseableLock.of(consumerToLocks.get(consumer))) {
        consumer.unSubscribe(pubSubTopicPartition, timeoutMs);
        removeTopicPartitionFromConsumptionTask(consumer, pubSubTopicPartition);
      }
      consumerPollTracker.removeTopicPartition(pubSubTopicPartition);
      versionTopicToTopicPartitionToConsumer.compute(versionTopic, (k, topicPartitionToConsumerMap) -> {
        if (topicPartitionToConsumerMap != null) {
          topicPartitionToConsumerMap.remove(pubSubTopicPartition);
          return topicPartitionToConsumerMap.isEmpty() ? null : topicPartitionToConsumerMap;
        } else {
          return null;
        }
      });
    }
  }

  @Override
  public void batchUnsubscribe(PubSubTopic versionTopic, Set<PubSubTopicPartition> topicPartitionsToUnSub) {
    Map<SharedKafkaConsumer, Set<PubSubTopicPartition>> consumerUnSubTopicPartitionSet = new HashMap<>();
    SharedKafkaConsumer consumer;
    for (PubSubTopicPartition topicPartition: topicPartitionsToUnSub) {
      consumer = getConsumerAssignedToVersionTopicPartition(versionTopic, topicPartition);
      if (consumer != null) {
        Set<PubSubTopicPartition> topicPartitionSet =
            consumerUnSubTopicPartitionSet.computeIfAbsent(consumer, k -> new HashSet<>());
        topicPartitionSet.add(topicPartition);
      }
      consumerPollTracker.removeTopicPartition(topicPartition);
    }
    /**
     * Leverage {@link PubSubConsumerAdapter#batchUnsubscribe(Set)}.
     */
    consumerUnSubTopicPartitionSet.forEach((sharedConsumer, tpSet) -> {
      ConsumptionTask task = consumerToConsumptionTask.get(sharedConsumer);
      /**
       * Refer {@link KafkaConsumerService#startConsumptionIntoDataReceiver} for avoiding race condition caused by
       * setting data receiver and unsubscribing concurrently for the same topic partition on a shared consumer.
       */
      try (AutoCloseableLock ignored = AutoCloseableLock.of(consumerToLocks.get(sharedConsumer))) {
        sharedConsumer.batchUnsubscribe(tpSet);
        tpSet.forEach(task::removeDataReceiver);
      }
      tpSet.forEach(
          tp -> versionTopicToTopicPartitionToConsumer.compute(versionTopic, (k, topicPartitionToConsumerMap) -> {
            if (topicPartitionToConsumerMap != null) {
              topicPartitionToConsumerMap.remove(tp);
              return topicPartitionToConsumerMap.isEmpty() ? null : topicPartitionToConsumerMap;
            } else {
              return null;
            }
          }));
    });
  }

  @Override
  public boolean startInner() {
    consumerToConsumptionTask.values().forEach(consumerExecutor::submit);
    consumerExecutor.shutdown();
    LOGGER.info("KafkaConsumerService started for {}", kafkaUrl);
    return true;
  }

  @Override
  public void stopInner() throws Exception {
    consumerToConsumptionTask.values().forEach(ConsumptionTask::stop);
    long beginningTime = System.currentTimeMillis();
    boolean gracefulShutdownSuccess = consumerExecutor.awaitTermination(SHUTDOWN_TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
    long gracefulShutdownDuration = System.currentTimeMillis() - beginningTime;
    if (gracefulShutdownSuccess) {
      LOGGER.info("consumerExecutor terminated gracefully in {} ms.", gracefulShutdownDuration);
    } else {
      LOGGER.warn(
          "consumerExecutor timed out after {} ms while awaiting graceful termination. Will force shutdown.",
          gracefulShutdownDuration);
      long forcefulShutdownBeginningTime = System.currentTimeMillis();
      consumerExecutor.shutdownNow();
      boolean forcefulShutdownSuccess = consumerExecutor.awaitTermination(SHUTDOWN_TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
      long forcefulShutdownDuration = System.currentTimeMillis() - forcefulShutdownBeginningTime;
      if (forcefulShutdownSuccess) {
        LOGGER.info("consumerExecutor terminated forcefully in {} ms.", forcefulShutdownDuration);
      } else {
        LOGGER.warn(
            "consumerExecutor timed out after {} ms while awaiting forceful termination.",
            forcefulShutdownDuration);
      }
    }
    beginningTime = System.currentTimeMillis();
    consumerToConsumptionTask.keySet().forEach(SharedKafkaConsumer::close);
    LOGGER.info("SharedKafkaConsumer closed in {} ms.", System.currentTimeMillis() - beginningTime);
  }

  @Override
  public boolean hasAnySubscriptionFor(PubSubTopic versionTopic) {
    Map<PubSubTopicPartition, SharedKafkaConsumer> subscriptions =
        versionTopicToTopicPartitionToConsumer.get(versionTopic);
    if (subscriptions == null) {
      return false;
    }
    return !subscriptions.isEmpty();
  }

  private AggKafkaConsumerServiceStats createAggKafkaConsumerServiceStats(
      MetricsRepository metricsRepository,
      String kafkaClusterAlias,
      LongSupplier getMaxElapsedTimeSinceLastPollInConsumerPool,
      ReadOnlyStoreRepository metadataRepository,
      boolean isUnregisterMetricForDeletedStoreEnabled) {
    String nameWithKafkaClusterAlias = "kafka_consumer_service_for_" + kafkaClusterAlias;
    return new AggKafkaConsumerServiceStats(
        nameWithKafkaClusterAlias,
        metricsRepository,
        metadataRepository,
        getMaxElapsedTimeSinceLastPollInConsumerPool,
        isUnregisterMetricForDeletedStoreEnabled);
  }

  @Override
  public long getMaxElapsedTimeMSSinceLastPollInConsumerPool() {
    long maxElapsedTimeSinceLastPollInConsumerPool = -1;
    int slowestTaskId = -1;
    long elapsedTimeSinceLastPoll;
    for (ConsumptionTask task: consumerToConsumptionTask.values()) {
      elapsedTimeSinceLastPoll = LatencyUtils.getElapsedTimeFromMsToMs(task.getLastSuccessfulPollTimestamp());
      if (elapsedTimeSinceLastPoll > maxElapsedTimeSinceLastPollInConsumerPool) {
        maxElapsedTimeSinceLastPollInConsumerPool = elapsedTimeSinceLastPoll;
        slowestTaskId = task.getTaskId();
      }
    }
    aggStats.recordTotalConsumerIdleTime(maxElapsedTimeSinceLastPollInConsumerPool);
    if (maxElapsedTimeSinceLastPollInConsumerPool > Time.MS_PER_MINUTE) {
      String slowestTaskIdString = kafkaUrl + slowestTaskId;
      if (!REDUNDANT_LOGGING_FILTER.isRedundantException(slowestTaskIdString)) {
        /**
         * We assume task id is the same as the number for thread. This is because both of them
         * are zero-based and ConsumptionTasks are submitted to the executor in order.
         */
        Thread slowestThread = threadFactory.getThread(slowestTaskId);
        SharedKafkaConsumer consumer = consumerToConsumptionTask.getByIndex(slowestTaskId).getKey();
        Map<PubSubTopicPartition, TopicPartitionIngestionInfo> topicPartitionIngestionInfoMap =
            getIngestionInfoFromConsumer(consumer);
        // Convert Map of ingestion info for this consumer to String for logging with each partition line by line
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<PubSubTopicPartition, TopicPartitionIngestionInfo> entry: topicPartitionIngestionInfoMap
            .entrySet()) {
          sb.append(entry.getKey().toString()).append(": ").append(entry.getValue().toString()).append("\n");
        }
        // log the slowest consumer id if it couldn't make any progress in a minute!
        LOGGER.warn(
            "Shared consumer ({} - task {}) couldn't make any progress for over {} ms, thread name: {}, stack trace:\n{}, consumer info:\n{}",
            kafkaUrl,
            slowestTaskId,
            maxElapsedTimeSinceLastPollInConsumerPool,
            slowestThread != null ? slowestThread.getName() : null,
            ExceptionUtils.threadToThrowableToString(slowestThread),
            sb.toString());
      }
    }
    return maxElapsedTimeSinceLastPollInConsumerPool;
  }

  @Override
  public void startConsumptionIntoDataReceiver(
      PartitionReplicaIngestionContext partitionReplicaIngestionContext,
      long lastReadOffset,
      ConsumedDataReceiver<List<DefaultPubSubMessage>> consumedDataReceiver) {
    PubSubTopic versionTopic = consumedDataReceiver.destinationIdentifier();
    PubSubTopicPartition topicPartition = partitionReplicaIngestionContext.getPubSubTopicPartition();
    SharedKafkaConsumer consumer = assignConsumerFor(versionTopic, topicPartition);
    if (consumer == null) {
      // Defensive code. Shouldn't happen except in case of a regression.
      throw new VeniceException(
          "Shared consumer must exist for version topic: " + versionTopic + " in Kafka cluster: " + kafkaUrl);
    }
    /**
     * It is possible that when one {@link StoreIngestionTask} thread finishes unsubscribing a topic partition but not
     * finish removing data receiver, but the other {@link StoreIngestionTask} thread is setting data receiver for this
     * topic partition before subscription. As {@link ConsumptionTask} does not allow 2 different data receivers for
     * the same topic partition, it will throw exception.
     */
    try (AutoCloseableLock ignored = AutoCloseableLock.of(consumerToLocks.get(consumer))) {
      ConsumptionTask consumptionTask = consumerToConsumptionTask.get(consumer);
      if (consumptionTask == null) {
        // Defensive coding. Should never happen except in case of a regression.
        throw new IllegalStateException(
            "There should be a " + ConsumptionTask.class.getSimpleName() + " assigned for this "
                + SharedKafkaConsumer.class.getSimpleName());
      }
      /**
       * N.B. it's important to set the {@link ConsumedDataReceiver} prior to subscribing, otherwise the
       * {@link KafkaConsumerService.ConsumptionTask} will not be able to funnel the messages.
       */
      consumptionTask.setDataReceiver(topicPartition, consumedDataReceiver);
      consumer.subscribe(consumedDataReceiver.destinationIdentifier(), topicPartition, lastReadOffset);
      consumerPollTracker.recordSubscribed(topicPartition);
    }
  }

  @Override
  public Map<PubSubTopicPartition, Long> getStaleTopicPartitions(long thresholdTimestamp) {
    return consumerPollTracker.getStaleTopicPartitions(thresholdTimestamp);
  }

  interface KCSConstructor {
    KafkaConsumerService construct(
        ConsumerPoolType poolType,
        PubSubConsumerAdapterFactory consumerFactory,
        Properties consumerProperties,
        long readCycleDelayMs,
        int numOfConsumersPerKafkaCluster,
        IngestionThrottler ingestionThrottler,
        KafkaClusterBasedRecordThrottler kafkaClusterBasedRecordThrottler,
        MetricsRepository metricsRepository,
        String kafkaClusterAlias,
        long sharedConsumerNonExistingTopicCleanupDelayMS,
        StaleTopicChecker staleTopicChecker,
        boolean liveConfigBasedKafkaThrottlingEnabled,
        PubSubMessageDeserializer pubSubDeserializer,
        Time time,
        AggKafkaConsumerServiceStats stats,
        boolean isKafkaConsumerOffsetCollectionEnabled,
        ReadOnlyStoreRepository metadataRepository,
        boolean unregisterMetricForDeletedStoreEnabled,
        VeniceServerConfig serverConfig);
  }

  /**
   * This metric function will be called when any {@link SharedKafkaConsumer} inside this class attempt to subscribe or
   * un-subscribe.
   */
  final void recordPartitionsPerConsumerSensor() {
    int totalPartitions = 0;
    int minPartitionsPerConsumer = Integer.MAX_VALUE;
    int maxPartitionsPerConsumer = Integer.MIN_VALUE;

    int subscribedPartitionCount;
    for (SharedKafkaConsumer consumer: consumerToConsumptionTask.keySet()) {
      subscribedPartitionCount = consumer.getAssignmentSize();
      totalPartitions += subscribedPartitionCount;
      minPartitionsPerConsumer = Math.min(minPartitionsPerConsumer, subscribedPartitionCount);
      maxPartitionsPerConsumer = Math.max(maxPartitionsPerConsumer, subscribedPartitionCount);
    }
    int avgPartitionsPerConsumer = totalPartitions / consumerToConsumptionTask.size();

    aggStats.recordTotalAvgPartitionsPerConsumer(avgPartitionsPerConsumer);
    aggStats.recordTotalMaxPartitionsPerConsumer(maxPartitionsPerConsumer);
    aggStats.recordTotalMinPartitionsPerConsumer(minPartitionsPerConsumer);
    aggStats.recordTotalSubscribedPartitionsNum(totalPartitions);
  }

  public long getOffsetLagBasedOnMetrics(PubSubTopic versionTopic, PubSubTopicPartition pubSubTopicPartition) {
    return getSomeOffsetFor(versionTopic, pubSubTopicPartition, PubSubConsumerAdapter::getOffsetLag);
  }

  public long getLatestOffsetBasedOnMetrics(PubSubTopic versionTopic, PubSubTopicPartition pubSubTopicPartition) {
    return getSomeOffsetFor(versionTopic, pubSubTopicPartition, PubSubConsumerAdapter::getLatestOffset);
  }

  private long getSomeOffsetFor(
      PubSubTopic versionTopic,
      PubSubTopicPartition pubSubTopicPartition,
      OffsetGetter offsetGetter) {
    PubSubConsumerAdapter consumer = getConsumerAssignedToVersionTopicPartition(versionTopic, pubSubTopicPartition);
    if (consumer == null) {
      return -1;
    } else {
      return offsetGetter.apply(consumer, pubSubTopicPartition);
    }
  }

  public Map<PubSubTopicPartition, TopicPartitionIngestionInfo> getIngestionInfoFor(
      PubSubTopic versionTopic,
      PubSubTopicPartition pubSubTopicPartition) {
    SharedKafkaConsumer consumer = getConsumerAssignedToVersionTopicPartition(versionTopic, pubSubTopicPartition);
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> topicPartitionIngestionInfoMap =
        getIngestionInfoFromConsumer(consumer);
    return topicPartitionIngestionInfoMap;
  }

  private Map<PubSubTopicPartition, TopicPartitionIngestionInfo> getIngestionInfoFromConsumer(
      SharedKafkaConsumer consumer) {
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> topicPartitionIngestionInfoMap = new HashMap<>();
    if (consumer != null) {
      ConsumptionTask consumptionTask = consumerToConsumptionTask.get(consumer);
      String consumerIdStr = consumptionTask.getTaskIdStr();
      for (PubSubTopicPartition topicPartition: consumer.getAssignment()) {
        long offsetLag = consumer.getOffsetLag(topicPartition);
        long latestOffset = consumer.getLatestOffset(topicPartition);
        ConsumptionTask.PartitionStats partitionStats = consumptionTask.getPartitionStats(topicPartition);
        double msgRate = partitionStats.getMessageRate();
        double byteRate = partitionStats.getBytesRate();
        long lastSuccessfulPollTimestamp = partitionStats.getLastSuccessfulPollTimestamp();
        long elapsedTimeSinceLastRecordForPartitionInMs = ConsumptionTask.DEFAULT_TOPIC_PARTITION_NO_POLL_TIMESTAMP;

        // Consumer level elapsed time
        long elapsedTimeSinceLastConsumerPollInMs =
            LatencyUtils.getElapsedTimeFromMsToMs(consumptionTask.getLastSuccessfulPollTimestamp());

        // Partition level elapsed time
        if (lastSuccessfulPollTimestamp > 0) {
          elapsedTimeSinceLastRecordForPartitionInMs =
              LatencyUtils.getElapsedTimeFromMsToMs(lastSuccessfulPollTimestamp);
        }
        PubSubTopic destinationVersionTopic = consumptionTask.getDestinationIdentifier(topicPartition);
        String destinationVersionTopicName = destinationVersionTopic == null ? "" : destinationVersionTopic.getName();
        TopicPartitionIngestionInfo topicPartitionIngestionInfo = new TopicPartitionIngestionInfo(
            latestOffset,
            offsetLag,
            msgRate,
            byteRate,
            consumerIdStr,
            elapsedTimeSinceLastConsumerPollInMs,
            elapsedTimeSinceLastRecordForPartitionInMs,
            destinationVersionTopicName);
        topicPartitionIngestionInfoMap.put(topicPartition, topicPartitionIngestionInfo);
      }
    }
    return topicPartitionIngestionInfoMap;
  }

  private interface OffsetGetter {
    long apply(PubSubConsumerAdapter consumer, PubSubTopicPartition pubSubTopicPartition);
  }

  /**
   * This consumer assignment strategy specify how consumers from consumer pool are allocated. Now we support two basic
   * strategies with topic-wise and partition-wise for supporting consumer shared in topic and topic-partition granularity,
   * respectively. Each strategy will have a specific extension of {@link KafkaConsumerService}.
   */
  public enum ConsumerAssignmentStrategy {
    @Deprecated
    TOPIC_WISE_SHARED_CONSUMER_ASSIGNMENT_STRATEGY(TopicWiseKafkaConsumerService::new),
    PARTITION_WISE_SHARED_CONSUMER_ASSIGNMENT_STRATEGY(PartitionWiseKafkaConsumerService::new),
    STORE_AWARE_PARTITION_WISE_SHARED_CONSUMER_ASSIGNMENT_STRATEGY(StoreAwarePartitionWiseKafkaConsumerService::new);

    final KCSConstructor constructor;

    ConsumerAssignmentStrategy(KCSConstructor constructor) {
      this.constructor = constructor;
    }
  }

  // For testing only
  public void setThreadFactory(RandomAccessDaemonThreadFactory threadFactory) {
    this.threadFactory = threadFactory;
  }

  IndexedMap<SharedKafkaConsumer, ConsumptionTask> getConsumerToConsumptionTask() {
    return consumerToConsumptionTask;
  }
}
