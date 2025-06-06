package com.linkedin.venice.ingestionHeartbeat;

import static com.linkedin.venice.message.KafkaKey.HEART_BEAT;
import static com.linkedin.venice.pubsub.api.PubSubMessageHeaders.VENICE_LEADER_COMPLETION_STATE_HEADER;
import static com.linkedin.venice.utils.TestUtils.assertCommand;
import static com.linkedin.venice.utils.TestWriteUtils.NAME_RECORD_V1_SCHEMA;
import static com.linkedin.venice.utils.TestWriteUtils.getTempDataDirectory;
import static com.linkedin.venice.utils.TestWriteUtils.writeSimpleAvroFileWithStringToNameRecordV1Schema;
import static com.linkedin.venice.vpj.VenicePushJobConstants.DEFAULT_KEY_FIELD_PROP;
import static com.linkedin.venice.vpj.VenicePushJobConstants.INCREMENTAL_PUSH;
import static com.linkedin.venice.vpj.VenicePushJobConstants.VENICE_STORE_NAME_PROP;
import static com.linkedin.venice.writer.LeaderCompleteState.LEADER_COMPLETED;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.ControllerResponse;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.controllerapi.VersionCreationResponse;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.hadoop.VenicePushJob;
import com.linkedin.venice.integration.utils.PubSubBrokerWrapper;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceControllerWrapper;
import com.linkedin.venice.integration.utils.VeniceMultiClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceMultiRegionClusterCreateOptions;
import com.linkedin.venice.integration.utils.VeniceTwoLayerMultiRegionMultiClusterWrapper;
import com.linkedin.venice.meta.DataReplicationPolicy;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.StoreInfo;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.pubsub.PubSubConsumerAdapterContext;
import com.linkedin.venice.pubsub.PubSubTopicPartitionImpl;
import com.linkedin.venice.pubsub.PubSubTopicRepository;
import com.linkedin.venice.pubsub.api.DefaultPubSubMessage;
import com.linkedin.venice.pubsub.api.PubSubConsumerAdapter;
import com.linkedin.venice.pubsub.api.PubSubMessageDeserializer;
import com.linkedin.venice.pubsub.api.PubSubMessageHeader;
import com.linkedin.venice.pubsub.api.PubSubMessageHeaders;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.utils.DataProviderUtils;
import com.linkedin.venice.utils.IntegrationTestPushUtils;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import com.linkedin.venice.writer.LeaderCompleteState;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;


/**
 * This class includes tests for verifying ingestion heartbeat in RT and VT
 */
public class IngestionHeartBeatTest {
  private static final int NUMBER_OF_CHILD_DATACENTERS = 2;
  private static final int NUMBER_OF_CLUSTERS = 1;
  private static final int TEST_TIMEOUT_MS = 120_000;
  private static final String CLUSTER_NAME = "venice-cluster0";
  private VeniceTwoLayerMultiRegionMultiClusterWrapper multiRegionMultiClusterWrapper;
  private VeniceControllerWrapper parentController;
  private List<VeniceMultiClusterWrapper> childDatacenters;
  private String storeName;

  @BeforeClass(alwaysRun = true)
  public void setUp() {
    Properties serverProperties = new Properties();
    Properties controllerProps = new Properties();
    controllerProps.put(ConfigKeys.CONTROLLER_AUTO_MATERIALIZE_META_SYSTEM_STORE, false);
    VeniceMultiRegionClusterCreateOptions.Builder optionsBuilder =
        new VeniceMultiRegionClusterCreateOptions.Builder().numberOfRegions(NUMBER_OF_CHILD_DATACENTERS)
            .numberOfClusters(NUMBER_OF_CLUSTERS)
            .numberOfParentControllers(1)
            .numberOfChildControllers(1)
            .numberOfServers(4)
            .numberOfRouters(1)
            .replicationFactor(2)
            .forkServer(false)
            .parentControllerProperties(controllerProps)
            .childControllerProperties(controllerProps)
            .serverProperties(serverProperties);
    this.multiRegionMultiClusterWrapper =
        ServiceFactory.getVeniceTwoLayerMultiRegionMultiClusterWrapper(optionsBuilder.build());
    this.childDatacenters = multiRegionMultiClusterWrapper.getChildRegions();
    List<VeniceControllerWrapper> parentControllers = multiRegionMultiClusterWrapper.getParentControllers();
    if (parentControllers.size() != 1) {
      throw new IllegalStateException("Expect only one parent controller. Got: " + parentControllers.size());
    }
    this.parentController = parentControllers.get(0);
  }

  @AfterTest(alwaysRun = true)
  public void cleanupStore() {
    if (this.parentController != null) {
      String parentControllerUrl = parentController.getControllerUrl();
      try (ControllerClient parentControllerClient =
          new ControllerClient(multiRegionMultiClusterWrapper.getClusterNames()[0], parentControllerUrl)) {
        parentControllerClient.disableAndDeleteStore(storeName);
      }
    }
  }

  @DataProvider
  public static Object[][] AAConfigAndIncPushAndDRPProvider() {
    return DataProviderUtils
        .allPermutationGenerator(DataProviderUtils.BOOLEAN, DataProviderUtils.BOOLEAN, DataReplicationPolicy.values());
  }

  @Test(dataProvider = "Two-True-and-False", timeOut = TEST_TIMEOUT_MS, dataProviderClass = DataProviderUtils.class)
  public void testIngestionHeartBeat(boolean isActiveActiveEnabled, boolean isIncrementalPushEnabled)
      throws IOException, InterruptedException {
    storeName = Utils.getUniqueString("ingestionHeartBeatTest");
    String parentControllerUrl = parentController.getControllerUrl();
    File inputDir = getTempDataDirectory();
    Schema recordSchema = writeSimpleAvroFileWithStringToNameRecordV1Schema(inputDir);
    String keySchemaStr = recordSchema.getField(DEFAULT_KEY_FIELD_PROP).schema().toString();
    String inputDirPath = "file://" + inputDir.getAbsolutePath();
    Properties vpjProperties =
        IntegrationTestPushUtils.defaultVPJProps(multiRegionMultiClusterWrapper, inputDirPath, storeName);
    if (isIncrementalPushEnabled) {
      vpjProperties.put(INCREMENTAL_PUSH, true);
    }

    try (ControllerClient parentControllerClient = new ControllerClient(CLUSTER_NAME, parentControllerUrl)) {
      assertCommand(
          parentControllerClient
              .createNewStore(storeName, "test_owner", keySchemaStr, NAME_RECORD_V1_SCHEMA.toString()));
      TestUtils.assertCommand(parentControllerClient.getStore(storeName));
      UpdateStoreQueryParams updateStoreParams =
          new UpdateStoreQueryParams().setStorageQuotaInByte(Store.UNLIMITED_STORAGE_QUOTA)
              .setCompressionStrategy(CompressionStrategy.NO_OP)
              .setIncrementalPushEnabled(isIncrementalPushEnabled)
              .setHybridRewindSeconds(500L)
              .setHybridOffsetLagThreshold(10L)
              .setPartitionCount(2)
              .setReplicationFactor(2)
              .setNativeReplicationEnabled(true)
              .setActiveActiveReplicationEnabled(isActiveActiveEnabled);

      ControllerResponse updateStoreResponse =
          parentControllerClient.retryableRequest(5, c -> c.updateStore(storeName, updateStoreParams));

      // If config combination for incremental push is wrong, update store should fail loudly.
      if (!isActiveActiveEnabled && isIncrementalPushEnabled) {
        assertTrue(updateStoreResponse.isError(), "Update store does not error on invalid config combination.");
        return;
      }
      assertFalse(updateStoreResponse.isError(), "Update store got error: " + updateStoreResponse.getError());

      VersionCreationResponse response = parentControllerClient.emptyPush(storeName, "test_push_id", 1000);
      assertEquals(response.getVersion(), 1);
      assertFalse(response.isError(), "Empty push to parent colo should succeed");
      TestUtils.waitForNonDeterministicPushCompletion(
          response.getKafkaTopic(),
          parentControllerClient,
          60,
          TimeUnit.SECONDS);

      // VPJ full push or incremental push
      int expectedVersionNumber = isIncrementalPushEnabled ? 1 : 2;
      String childControllerUrl = childDatacenters.get(0).getRandomController().getControllerUrl();
      try (ControllerClient childControllerClient = new ControllerClient(CLUSTER_NAME, childControllerUrl)) {
        runVPJ(vpjProperties, expectedVersionNumber, childControllerClient);
      }
      VeniceClusterWrapper veniceClusterWrapper = childDatacenters.get(0).getClusters().get(CLUSTER_NAME);
      veniceClusterWrapper.waitVersion(storeName, expectedVersionNumber);

      // Verify data pushed via full push/inc push using client
      try (AvroGenericStoreClient<Object, Object> storeReader = ClientFactory.getAndStartGenericAvroClient(
          ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(veniceClusterWrapper.getRandomRouterURL()))) {
        TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, true, () -> {
          try {
            for (int i = 1; i < 100; i++) {
              String key = String.valueOf(i);
              GenericRecord value = readValue(storeReader, key);
              assertNotNull(value, "Key " + key + " should not be missing!");
              assertEquals(value.get("firstName").toString(), "first_name_" + key);
              assertEquals(value.get("lastName").toString(), "last_name_" + key);
            }
          } catch (Exception e) {
            throw new VeniceException(e);
          }
        });
      }

      StoreInfo storeInfo = TestUtils.assertCommand(parentControllerClient.getStore(storeName)).getStore();

      // create consumer to consume from RT/VT to verify HB and Leader completed header
      for (int dc = 0; dc < NUMBER_OF_CHILD_DATACENTERS; dc++) {
        PubSubBrokerWrapper pubSubBrokerWrapper =
            childDatacenters.get(dc).getClusters().get(CLUSTER_NAME).getPubSubBrokerWrapper();

        Properties properties = new Properties();
        properties.setProperty(ConfigKeys.KAFKA_BOOTSTRAP_SERVERS, pubSubBrokerWrapper.getAddress());
        try (PubSubConsumerAdapter pubSubConsumer = pubSubBrokerWrapper.getPubSubClientsFactory()
            .getConsumerAdapterFactory()
            .create(
                new PubSubConsumerAdapterContext.Builder().setVeniceProperties(new VeniceProperties(properties))
                    .setPubSubMessageDeserializer(PubSubMessageDeserializer.createDefaultDeserializer())
                    .setPubSubPositionTypeRegistry(pubSubBrokerWrapper.getPubSubPositionTypeRegistry())
                    .setConsumerName("testConsumer")
                    .build())) {
          for (int partition = 0; partition < response.getPartitions(); partition++) {
            // RT: verify HB is received
            verifyHBinKafkaTopic(pubSubConsumer, storeInfo, partition, isIncrementalPushEnabled, true);

            // VT: verify leader topic partition receives HB from RT, and is forwarded with leader completed
            // header to all VT.
            verifyHBinKafkaTopic(pubSubConsumer, storeInfo, partition, isIncrementalPushEnabled, false);
          }
        }
      }
    }
  }

  private void verifyHBinKafkaTopic(
      PubSubConsumerAdapter pubSubConsumer,
      StoreInfo storeInfo,
      int partition,
      boolean isIncrementalPushEnabled,
      boolean isRealTime) throws InterruptedException {
    String topicToSubscribeTo = isRealTime
        ? Utils.getRealTimeTopicName(storeInfo)
        : Version.composeKafkaTopic(storeName, isIncrementalPushEnabled ? 1 : 2);
    pubSubConsumer.subscribe(
        new PubSubTopicPartitionImpl(new PubSubTopicRepository().getTopic(topicToSubscribeTo), partition),
        0);
    AtomicBoolean isHBFound = new AtomicBoolean(false);
    AtomicBoolean isLeaderCompletionHeaderFound = new AtomicBoolean(false);
    AtomicBoolean isLeaderCompleted = new AtomicBoolean(false);
    TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, () -> {
      Map<PubSubTopicPartition, List<DefaultPubSubMessage>> messages = pubSubConsumer.poll(100 * Time.MS_PER_SECOND);
      for (Map.Entry<PubSubTopicPartition, List<DefaultPubSubMessage>> entry: messages.entrySet()) {
        List<DefaultPubSubMessage> pubSubMessages = entry.getValue();
        for (DefaultPubSubMessage message: pubSubMessages) {
          if (Arrays.equals(message.getKey().getKey(), HEART_BEAT.getKey())) {
            isHBFound.set(true);
          }
          PubSubMessageHeaders pubSubMessageHeaders = message.getPubSubMessageHeaders();
          for (PubSubMessageHeader header: pubSubMessageHeaders.toList()) {
            if (header.key().equals(VENICE_LEADER_COMPLETION_STATE_HEADER)) {
              isLeaderCompletionHeaderFound.set(true);
              if (LeaderCompleteState.valueOf(header.value()[0]) == LEADER_COMPLETED) {
                isLeaderCompleted.set(true);
                break;
              }
            }
          }
        }
        if (isLeaderCompleted.get()) {
          break;
        }
      }
      assertTrue(
          isHBFound.get(),
          String.format("Heartbeat not found in %s partition %d", isRealTime ? "RT" : "VT", partition));
      if (isRealTime) {
        assertFalse(
            isLeaderCompletionHeaderFound.get(),
            String.format("Leader completed header found in RT partition %d", partition));
        assertFalse(
            isLeaderCompleted.get(),
            String.format("Leader completed header set to completed in RT partition %d", partition));
      } else {
        assertTrue(
            isLeaderCompletionHeaderFound.get(),
            String.format("Leader completed header not found in VT partition %d", partition));
        assertTrue(
            isLeaderCompleted.get(),
            String.format("Leader completed header not set to completed in VT partition %d", partition));
      }

    });

    pubSubConsumer
        .unSubscribe(new PubSubTopicPartitionImpl(new PubSubTopicRepository().getTopic(topicToSubscribeTo), partition));
    while (pubSubConsumer.hasAnySubscription()) {
      Thread.sleep(1000);
    }
  }

  /**
   * Blocking, waits for new version to go online
   */
  private void runVPJ(Properties vpjProperties, int expectedVersionNumber, ControllerClient controllerClient) {
    String jobName = Utils.getUniqueString("incPushJob-" + expectedVersionNumber);
    try (VenicePushJob job = new VenicePushJob(jobName, vpjProperties)) {
      job.run();
      TestUtils.waitForNonDeterministicCompletion(
          60,
          TimeUnit.SECONDS,
          () -> controllerClient.getStore((String) vpjProperties.get(VENICE_STORE_NAME_PROP))
              .getStore()
              .getCurrentVersion() == expectedVersionNumber);
    }
  }

  private GenericRecord readValue(AvroGenericStoreClient<Object, Object> storeReader, String key)
      throws ExecutionException, InterruptedException {
    return (GenericRecord) storeReader.get(key).get();
  }

  @AfterClass(alwaysRun = true)
  public void cleanUp() {
    Utils.closeQuietlyWithErrorLogged(multiRegionMultiClusterWrapper);
  }
}
