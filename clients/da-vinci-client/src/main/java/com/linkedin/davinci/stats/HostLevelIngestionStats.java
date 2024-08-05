package com.linkedin.davinci.stats;

import com.linkedin.davinci.config.VeniceServerConfig;
import com.linkedin.davinci.kafka.consumer.PartitionConsumptionState;
import com.linkedin.davinci.kafka.consumer.StoreIngestionTask;
import com.linkedin.venice.stats.AbstractVeniceStats;
import com.linkedin.venice.stats.LongAdderRateGauge;
import com.linkedin.venice.stats.TehutiUtils;
import com.linkedin.venice.utils.RegionUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.lazy.Lazy;
import io.tehuti.metrics.MetricsRepository;
import io.tehuti.metrics.Sensor;
import io.tehuti.metrics.stats.AsyncGauge;
import io.tehuti.metrics.stats.Avg;
import io.tehuti.metrics.stats.Count;
import io.tehuti.metrics.stats.Max;
import io.tehuti.metrics.stats.Min;
import io.tehuti.metrics.stats.OccurrenceRate;
import io.tehuti.metrics.stats.Rate;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;


/**
 * This class contains stats for stats on the storage node host level.
 * Stats inside this class can either:
 * (1) Total only: The stat indicate total number of all the stores on this host.
 * (2) Per store only: The stat is registered for each store on this host.
 * (3) Per store and total: The stat is registered for each store on this host and the total number for this host.
 */
public class HostLevelIngestionStats extends AbstractVeniceStats {
  public static final String ASSEMBLED_RECORD_VALUE_SIZE_IN_BYTES = "assembled_record_value_size_in_bytes";

  // The aggregated bytes ingested rate for the entire host
  private final Lazy<LongAdderRateGauge> totalBytesConsumedRate;
  // The aggregated records ingested rate for the entire host
  private final Lazy<LongAdderRateGauge> totalRecordsConsumedRate;

  /*
   * Bytes read from Kafka by store ingestion task as a total. This metric includes bytes read for all store versions
   * allocated in a storage node reported with its uncompressed data size.
   */
  private final Lazy<LongAdderRateGauge> totalBytesReadFromKafkaAsUncompressedSizeRate;

  /** To measure 'put' latency of consumer records blocking queue */
  private final Lazy<Sensor> consumerRecordsQueuePutLatencySensor;
  private final Lazy<Sensor> keySizeSensor;
  private final Lazy<Sensor> valueSizeSensor;
  private final Lazy<Sensor> assembledValueSizeSensor;
  private final Lazy<Sensor> unexpectedMessageSensor;
  private final Lazy<Sensor> inconsistentStoreMetadataSensor;
  private final Lazy<Sensor> ingestionFailureSensor;

  private final Lazy<Sensor> viewProducerLatencySensor;
  /**
   * Sensors for emitting if/when we detect DCR violations (such as a backwards timestamp or receding offset vector)
   */
  private final Lazy<LongAdderRateGauge> totalTimestampRegressionDCRErrorRate;
  private final Lazy<LongAdderRateGauge> totalOffsetRegressionDCRErrorRate;
  /**
   * A gauge reporting the total the percentage of hybrid quota used.
   */
  private double hybridQuotaUsageGauge;
  // Measure the avg/max time we need to spend on waiting for the leader producer
  private final Lazy<Sensor> leaderProducerSynchronizeLatencySensor;
  // Measure the avg/max latency for data lookup and deserialization
  private final Lazy<Sensor> leaderWriteComputeLookUpLatencySensor;
  // Measure the avg/max latency for the actual write computation
  private final Lazy<Sensor> leaderWriteComputeUpdateLatencySensor;
  // Measure the latency in processing consumer actions
  private final Lazy<Sensor> processConsumerActionLatencySensor;
  // Measure the latency in checking long running task states, like leader promotion, TopicSwitch
  private final Lazy<Sensor> checkLongRunningTasksLatencySensor;
  // Measure the latency in putting data into storage engine
  private final Lazy<Sensor> storageEnginePutLatencySensor;
  // Measure the latency in deleting data from storage engine
  private final Lazy<Sensor> storageEngineDeleteLatencySensor;

  /**
   * Measure the number of times a record was found in {@link PartitionConsumptionState#transientRecordMap} during UPDATE
   * message processing.
   */
  private final Lazy<Sensor> writeComputeCacheHitCount;

  private final Lazy<LongAdderRateGauge> totalLeaderBytesConsumedRate;
  private final Lazy<LongAdderRateGauge> totalLeaderRecordsConsumedRate;
  private final Lazy<LongAdderRateGauge> totalFollowerBytesConsumedRate;
  private final Lazy<LongAdderRateGauge> totalFollowerRecordsConsumedRate;
  private final Lazy<LongAdderRateGauge> totalLeaderBytesProducedRate;
  private final Lazy<LongAdderRateGauge> totalLeaderRecordsProducedRate;
  private final List<Sensor> totalHybridBytesConsumedByRegionId;
  private final List<Sensor> totalHybridRecordsConsumedByRegionId;

  private final Lazy<Sensor> checksumVerificationFailureSensor;

  /**
   * Measure the number of times replication metadata was found in {@link PartitionConsumptionState#transientRecordMap}
   */
  private final Lazy<Sensor> leaderIngestionReplicationMetadataCacheHitCount;

  /**
   * Measure the avg/max latency for value bytes lookup
   */
  private final Lazy<Sensor> leaderIngestionValueBytesLookUpLatencySensor;

  /**
   * Measure the number of times value bytes were found in {@link PartitionConsumptionState#transientRecordMap}
   */
  private final Lazy<Sensor> leaderIngestionValueBytesCacheHitCount;

  /**
   * Measure the avg/max latency for replication metadata data lookup
   */
  private final Lazy<Sensor> leaderIngestionReplicationMetadataLookUpLatencySensor;

  private final Lazy<Sensor> leaderIngestionActiveActivePutLatencySensor;

  private final Lazy<Sensor> leaderIngestionActiveActiveUpdateLatencySensor;

  private final Lazy<Sensor> leaderIngestionActiveActiveDeleteLatencySensor;

  /**
   * Measure the count of ignored updates due to conflict resolution
   */
  private final Lazy<LongAdderRateGauge> totalUpdateIgnoredDCRRate;

  /**
   * Measure the count of tombstones created
   */
  private final Lazy<LongAdderRateGauge> totalTombstoneCreationDCRRate;

  /**
   * @param totalStats the total stats singleton instance, or null if we are constructing the total stats
   */
  public HostLevelIngestionStats(
      MetricsRepository metricsRepository,
      VeniceServerConfig serverConfig,
      String storeName,
      HostLevelIngestionStats totalStats,
      Map<String, StoreIngestionTask> ingestionTaskMap,
      Time time) {
    super(metricsRepository, storeName);

    // Stats which are total only:

    this.totalBytesConsumedRate = Lazy
        .of(() -> registerOnlyTotalRate("bytes_consumed", totalStats, totalStats.totalBytesConsumedRate::get, time));

    this.totalRecordsConsumedRate = Lazy.of(
        () -> registerOnlyTotalRate("records_consumed", totalStats, totalStats.totalRecordsConsumedRate::get, time));

    this.totalBytesReadFromKafkaAsUncompressedSizeRate = Lazy.of(
        () -> registerOnlyTotalRate(
            "bytes_read_from_kafka_as_uncompressed_size",
            totalStats,
            totalStats.totalBytesReadFromKafkaAsUncompressedSizeRate::get,
            time));

    this.totalLeaderBytesConsumedRate = Lazy.of(
        () -> registerOnlyTotalRate(
            "leader_bytes_consumed",
            totalStats,
            totalStats.totalLeaderBytesConsumedRate::get,
            time));

    this.totalLeaderRecordsConsumedRate = Lazy.of(
        () -> registerOnlyTotalRate(
            "leader_records_consumed",
            totalStats,
            totalStats.totalLeaderRecordsConsumedRate::get,
            time));

    this.totalFollowerBytesConsumedRate = Lazy.of(
        () -> registerOnlyTotalRate(
            "follower_bytes_consumed",
            totalStats,
            totalStats.totalFollowerBytesConsumedRate::get,
            time));

    this.totalFollowerRecordsConsumedRate = Lazy.of(
        () -> registerOnlyTotalRate(
            "follower_records_consumed",
            totalStats,
            totalStats.totalFollowerRecordsConsumedRate::get,
            time));

    this.totalLeaderBytesProducedRate = Lazy.of(
        () -> registerOnlyTotalRate(
            "leader_bytes_produced",
            totalStats,
            totalStats.totalLeaderBytesProducedRate::get,
            time));

    this.totalLeaderRecordsProducedRate = Lazy.of(
        () -> registerOnlyTotalRate(
            "leader_records_produced",
            totalStats,
            totalStats.totalLeaderRecordsProducedRate::get,
            time));

    this.totalUpdateIgnoredDCRRate = Lazy.of(
        () -> registerOnlyTotalRate("update_ignored_dcr", totalStats, totalStats.totalUpdateIgnoredDCRRate::get, time));

    this.totalTombstoneCreationDCRRate = Lazy.of(
        () -> registerOnlyTotalRate(
            "tombstone_creation_dcr",
            totalStats,
            totalStats.totalTombstoneCreationDCRRate::get,
            time));

    this.totalTimestampRegressionDCRErrorRate = Lazy.of(
        () -> registerOnlyTotalRate(
            "timestamp_regression_dcr_error",
            totalStats,
            totalStats.totalTimestampRegressionDCRErrorRate::get,
            time));

    this.totalOffsetRegressionDCRErrorRate = Lazy.of(
        () -> registerOnlyTotalRate(
            "offset_regression_dcr_error",
            totalStats,
            totalStats.totalOffsetRegressionDCRErrorRate::get,
            time));

    Int2ObjectMap<String> kafkaClusterIdToAliasMap = serverConfig.getKafkaClusterIdToAliasMap();
    int listSize = kafkaClusterIdToAliasMap.isEmpty() ? 0 : Collections.max(kafkaClusterIdToAliasMap.keySet()) + 1;
    Sensor[] tmpTotalHybridBytesConsumedByRegionId = new Sensor[listSize];
    Sensor[] tmpTotalHybridRecordsConsumedByRegionId = new Sensor[listSize];

    for (Int2ObjectMap.Entry<String> entry: serverConfig.getKafkaClusterIdToAliasMap().int2ObjectEntrySet()) {
      String regionNamePrefix =
          RegionUtils.getRegionSpecificMetricPrefix(serverConfig.getRegionName(), entry.getValue());
      tmpTotalHybridBytesConsumedByRegionId[entry.getIntKey()] =
          registerSensor(regionNamePrefix + "_rt_bytes_consumed", new Rate());
      tmpTotalHybridRecordsConsumedByRegionId[entry.getIntKey()] =
          registerSensor(regionNamePrefix + "_rt_records_consumed", new Rate());
    }
    this.totalHybridBytesConsumedByRegionId =
        Collections.unmodifiableList(Arrays.asList(tmpTotalHybridBytesConsumedByRegionId));
    this.totalHybridRecordsConsumedByRegionId =
        Collections.unmodifiableList(Arrays.asList(tmpTotalHybridRecordsConsumedByRegionId));

    // Register an aggregate metric for disk_usage_in_bytes metric
    final boolean isTotalStats = isTotalStats();
    registerSensor(
        new AsyncGauge(
            (ignored, ignored2) -> ingestionTaskMap.values()
                .stream()
                .filter(task -> isTotalStats ? true : task.getStoreName().equals(storeName))
                .mapToLong(
                    task -> isTotalStats
                        ? task.getStorageEngine().getCachedStoreSizeInBytes()
                        : task.getStorageEngine().getStoreSizeInBytes())
                .sum(),
            "disk_usage_in_bytes"));
    // Register an aggregate metric for rmd_disk_usage_in_bytes metric
    registerSensor(
        new AsyncGauge(
            (ignored, ignored2) -> ingestionTaskMap.values()
                .stream()
                .filter(task -> isTotalStats ? true : task.getStoreName().equals(storeName))
                .mapToLong(
                    task -> isTotalStats
                        ? task.getStorageEngine().getCachedRMDSizeInBytes()
                        : task.getStorageEngine().getRMDSizeInBytes())
                .sum(),
            "rmd_disk_usage_in_bytes"));
    registerSensor(
        new AsyncGauge(
            (ignored, ignored2) -> ingestionTaskMap.values()
                .stream()
                .filter(task -> isTotalStats ? true : task.getStoreName().equals(storeName))
                .mapToLong(task -> task.isStuckByMemoryConstraint() ? 1 : 0)
                .sum(),
            "ingestion_stuck_by_memory_constraint"));

    // Stats which are per-store only:
    String keySizeSensorName = "record_key_size_in_bytes";
    this.keySizeSensor = Lazy.of(
        () -> registerSensor(
            keySizeSensorName,
            new Avg(),
            new Min(),
            new Max(),
            TehutiUtils.getPercentileStat(getName() + AbstractVeniceStats.DELIMITER + keySizeSensorName)));

    String valueSizeSensorName = "record_value_size_in_bytes";
    this.valueSizeSensor = Lazy.of(
        () -> registerSensor(
            valueSizeSensorName,
            new Avg(),
            new Min(),
            new Max(),
            TehutiUtils.getPercentileStat(getName() + AbstractVeniceStats.DELIMITER + valueSizeSensorName)));

    this.assembledValueSizeSensor = Lazy.of(
        () -> registerSensor(
            ASSEMBLED_RECORD_VALUE_SIZE_IN_BYTES,
            new Avg(),
            new Min(),
            new Max(),
            TehutiUtils
                .getPercentileStat(getName() + AbstractVeniceStats.DELIMITER + ASSEMBLED_RECORD_VALUE_SIZE_IN_BYTES)));

    String viewTimerSensorName = "total_view_writer_latency";
    this.viewProducerLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            viewTimerSensorName,
            totalStats,
            totalStats.viewProducerLatencySensor::get,
            avgAndMax()));

    registerSensor(
        "storage_quota_used",
        new AsyncGauge((ignored, ignored2) -> hybridQuotaUsageGauge, "storage_quota_used"));

    // Stats which are both per-store and total:

    this.consumerRecordsQueuePutLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "consumer_records_queue_put_latency",
            totalStats,
            totalStats.consumerRecordsQueuePutLatencySensor::get,
            avgAndMax()));

    this.unexpectedMessageSensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "unexpected_message",
            totalStats,
            totalStats.unexpectedMessageSensor::get,
            new Rate()));

    this.inconsistentStoreMetadataSensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "inconsistent_store_metadata",
            totalStats,
            totalStats.inconsistentStoreMetadataSensor::get,
            new Count()));

    this.ingestionFailureSensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "ingestion_failure",
            totalStats,
            totalStats.ingestionFailureSensor::get,
            new Count()));

    this.leaderProducerSynchronizeLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "leader_producer_synchronize_latency",
            totalStats,
            totalStats.leaderProducerSynchronizeLatencySensor::get,
            avgAndMax()));

    this.leaderWriteComputeLookUpLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "leader_write_compute_lookup_latency",
            totalStats,
            totalStats.leaderWriteComputeLookUpLatencySensor::get,
            avgAndMax()));

    this.leaderWriteComputeUpdateLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "leader_write_compute_update_latency",
            totalStats,
            totalStats.leaderWriteComputeUpdateLatencySensor::get,
            avgAndMax()));

    this.processConsumerActionLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "process_consumer_actions_latency",
            totalStats,
            totalStats.processConsumerActionLatencySensor::get,
            avgAndMax()));

    this.checkLongRunningTasksLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "check_long_running_task_latency",
            totalStats,
            totalStats.checkLongRunningTasksLatencySensor::get,
            avgAndMax()));

    String storageEnginePutLatencySensorName = "storage_engine_put_latency",
        storageEngineDeleteLatencySensorName = "storage_engine_delete_latency";
    this.storageEnginePutLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            storageEnginePutLatencySensorName,
            totalStats,
            totalStats.storageEnginePutLatencySensor::get,
            new Avg(),
            new Max(),
            TehutiUtils
                .getPercentileStat(getName() + AbstractVeniceStats.DELIMITER + storageEnginePutLatencySensorName)));

    this.storageEngineDeleteLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            storageEngineDeleteLatencySensorName,
            totalStats,
            totalStats.storageEngineDeleteLatencySensor::get,
            new Avg(),
            new Max(),
            TehutiUtils
                .getPercentileStat(getName() + AbstractVeniceStats.DELIMITER + storageEngineDeleteLatencySensorName)));

    this.writeComputeCacheHitCount = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "write_compute_cache_hit_count",
            totalStats,
            totalStats.writeComputeCacheHitCount::get,
            new OccurrenceRate()));

    this.checksumVerificationFailureSensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "checksum_verification_failure",
            totalStats,
            totalStats.checksumVerificationFailureSensor::get,
            new Count()));

    this.leaderIngestionValueBytesLookUpLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "leader_ingestion_value_bytes_lookup_latency",
            totalStats,
            totalStats.leaderIngestionValueBytesLookUpLatencySensor::get,
            avgAndMax()));

    this.leaderIngestionValueBytesCacheHitCount = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "leader_ingestion_value_bytes_cache_hit_count",
            totalStats,
            totalStats.leaderIngestionValueBytesCacheHitCount::get,
            new Rate()));

    this.leaderIngestionReplicationMetadataCacheHitCount = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "leader_ingestion_replication_metadata_cache_hit_count",
            totalStats,
            totalStats.leaderIngestionReplicationMetadataCacheHitCount::get,
            new Rate()));

    this.leaderIngestionReplicationMetadataLookUpLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "leader_ingestion_replication_metadata_lookup_latency",
            totalStats,
            totalStats.leaderIngestionReplicationMetadataLookUpLatencySensor::get,
            avgAndMax()));

    this.leaderIngestionActiveActivePutLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "leader_ingestion_active_active_put_latency",
            totalStats,
            totalStats.leaderIngestionActiveActivePutLatencySensor::get,
            avgAndMax()));

    this.leaderIngestionActiveActiveUpdateLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "leader_ingestion_active_active_update_latency",
            totalStats,
            totalStats.leaderIngestionActiveActiveUpdateLatencySensor::get,
            avgAndMax()));

    this.leaderIngestionActiveActiveDeleteLatencySensor = Lazy.of(
        () -> registerPerStoreAndTotalSensor(
            "leader_ingestion_active_active_delete_latency",
            totalStats,
            totalStats.leaderIngestionActiveActiveDeleteLatencySensor::get,
            avgAndMax()));
  }

  /** Record a host-level byte consumption rate across all store versions */
  public void recordTotalBytesConsumed(long bytes) {
    totalBytesConsumedRate.get().record(bytes);
  }

  /** Record a host-level record consumption rate across all store versions */
  public void recordTotalRecordsConsumed() {
    totalRecordsConsumedRate.get().record();
  }

  public void recordTotalBytesReadFromKafkaAsUncompressedSize(long bytes) {
    totalBytesReadFromKafkaAsUncompressedSizeRate.get().record(bytes);
  }

  public void recordStorageQuotaUsed(double quotaUsed) {
    hybridQuotaUsageGauge = quotaUsed;
  }

  public void recordConsumerRecordsQueuePutLatency(double latency, long currentTimeMs) {
    consumerRecordsQueuePutLatencySensor.get().record(latency, currentTimeMs);
  }

  public void recordViewProducerLatency(double latency) {
    viewProducerLatencySensor.get().record(latency);
  }

  public void recordUnexpectedMessage() {
    unexpectedMessageSensor.get().record();
  }

  public void recordInconsistentStoreMetadata() {
    inconsistentStoreMetadataSensor.get().record();
  }

  public void recordKeySize(long bytes, long currentTimeMs) {
    keySizeSensor.get().record(bytes, currentTimeMs);
  }

  public void recordValueSize(long bytes, long currentTimeMs) {
    valueSizeSensor.get().record(bytes, currentTimeMs);
  }

  public void recordAssembledValueSize(long bytes, long currentTimeMs) {
    assembledValueSizeSensor.get().record(bytes, currentTimeMs);
  }

  public void recordIngestionFailure() {
    ingestionFailureSensor.get().record();
  }

  public void recordLeaderProducerSynchronizeLatency(double latency) {
    leaderProducerSynchronizeLatencySensor.get().record(latency);
  }

  public void recordWriteComputeLookUpLatency(double latency) {
    leaderWriteComputeLookUpLatencySensor.get().record(latency);
  }

  public void recordIngestionValueBytesLookUpLatency(double latency, long currentTime) {
    leaderIngestionValueBytesLookUpLatencySensor.get().record(latency, currentTime);
  }

  public void recordIngestionValueBytesCacheHitCount(long currentTime) {
    leaderIngestionValueBytesCacheHitCount.get().record(1, currentTime);
  }

  public void recordIngestionReplicationMetadataLookUpLatency(double latency, long currentTimeMs) {
    leaderIngestionReplicationMetadataLookUpLatencySensor.get().record(latency, currentTimeMs);
  }

  public void recordIngestionActiveActivePutLatency(double latency) {
    leaderIngestionActiveActivePutLatencySensor.get().record(latency);
  }

  public void recordIngestionActiveActiveUpdateLatency(double latency) {
    leaderIngestionActiveActiveUpdateLatencySensor.get().record(latency);
  }

  public void recordIngestionActiveActiveDeleteLatency(double latency) {
    leaderIngestionActiveActiveDeleteLatencySensor.get().record(latency);
  }

  public void recordWriteComputeUpdateLatency(double latency) {
    leaderWriteComputeUpdateLatencySensor.get().record(latency);
  }

  public void recordProcessConsumerActionLatency(double latency) {
    processConsumerActionLatencySensor.get().record(latency);
  }

  public void recordCheckLongRunningTasksLatency(double latency) {
    checkLongRunningTasksLatencySensor.get().record(latency);
  }

  public void recordStorageEnginePutLatency(double latency, long currentTimeMs) {
    storageEnginePutLatencySensor.get().record(latency, currentTimeMs);
  }

  public void recordStorageEngineDeleteLatency(double latency, long currentTimeMs) {
    storageEngineDeleteLatencySensor.get().record(latency, currentTimeMs);
  }

  public void recordWriteComputeCacheHitCount() {
    writeComputeCacheHitCount.get().record();
  }

  public void recordIngestionReplicationMetadataCacheHitCount(long currentTimeMs) {
    leaderIngestionReplicationMetadataCacheHitCount.get().record(1, currentTimeMs);
  }

  public void recordUpdateIgnoredDCR() {
    totalUpdateIgnoredDCRRate.get().record();
  }

  public void recordTombstoneCreatedDCR() {
    totalTombstoneCreationDCRRate.get().record();
  }

  public void recordTotalLeaderBytesConsumed(long bytes) {
    totalLeaderBytesConsumedRate.get().record(bytes);
  }

  public void recordTotalLeaderRecordsConsumed() {
    totalLeaderRecordsConsumedRate.get().record();
  }

  public void recordTotalFollowerBytesConsumed(long bytes) {
    totalFollowerBytesConsumedRate.get().record(bytes);
  }

  public void recordTotalFollowerRecordsConsumed() {
    totalFollowerRecordsConsumedRate.get().record();
  }

  public void recordTotalRegionHybridBytesConsumed(int regionId, long bytes, long currentTimeMs) {
    Sensor sensor = totalHybridBytesConsumedByRegionId.get(regionId);
    if (sensor != null) {
      sensor.record(bytes, currentTimeMs);
    }

    sensor = totalHybridRecordsConsumedByRegionId.get(regionId);
    if (sensor != null) {
      sensor.record(1, currentTimeMs);
    }
  }

  public void recordTotalLeaderBytesProduced(long bytes) {
    totalLeaderBytesProducedRate.get().record(bytes);
  }

  public void recordTotalLeaderRecordsProduced(int count) {
    totalLeaderRecordsProducedRate.get().record(count);
  }

  public void recordChecksumVerificationFailure() {
    checksumVerificationFailureSensor.get().record();
  }

  public void recordTimestampRegressionDCRError() {
    totalTimestampRegressionDCRErrorRate.get().record();
  }

  public void recordOffsetRegressionDCRError() {
    totalOffsetRegressionDCRErrorRate.get().record();
  }
}
