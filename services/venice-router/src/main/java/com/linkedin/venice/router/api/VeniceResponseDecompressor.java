package com.linkedin.venice.router.api;

import static com.linkedin.venice.read.RequestType.MULTI_GET;
import static com.linkedin.venice.read.RequestType.MULTI_GET_STREAMING;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY;
import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;

import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.compression.CompressorFactory;
import com.linkedin.venice.compression.VeniceCompressor;
import com.linkedin.venice.meta.StoreVersionName;
import com.linkedin.venice.read.RequestType;
import com.linkedin.venice.read.protocol.response.MultiGetResponseRecordV1;
import com.linkedin.venice.router.stats.AggRouterHttpRequestStats;
import com.linkedin.venice.router.stats.RouterStats;
import com.linkedin.venice.serializer.FastSerializerDeserializerFactory;
import com.linkedin.venice.serializer.RecordDeserializer;
import com.linkedin.venice.serializer.RecordSerializer;
import com.linkedin.venice.utils.LatencyUtils;
import com.linkedin.venice.utils.Pair;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.apache.avro.io.OptimizedBinaryDecoderFactory;


/**
 * This class is used to handle all the decompression related logic in Router, and it will be used in
 * {@link VeniceResponseAggregator} for regular requests and {@link VeniceDispatcher} for streaming requests.
 */
public class VeniceResponseDecompressor {
  private static final RecordSerializer<MultiGetResponseRecordV1> recordSerializer =
      FastSerializerDeserializerFactory.getFastAvroGenericSerializer(MultiGetResponseRecordV1.getClassSchema());
  private static final RecordDeserializer<MultiGetResponseRecordV1> recordDeserializer =
      FastSerializerDeserializerFactory
          .getFastAvroSpecificDeserializer(MultiGetResponseRecordV1.getClassSchema(), MultiGetResponseRecordV1.class);

  private final CompressionStrategy clientCompression;
  private final RouterStats<AggRouterHttpRequestStats> routerStats;
  private final StoreVersionName storeVersionName;
  private final CompressorFactory compressorFactory;

  public VeniceResponseDecompressor(
      CompressionStrategy clientCompression,
      RouterStats<AggRouterHttpRequestStats> routerStats,
      StoreVersionName storeVersionName,
      CompressorFactory compressorFactory) {
    this.routerStats = routerStats;
    this.clientCompression = clientCompression;
    this.storeVersionName = storeVersionName;
    this.compressorFactory = compressorFactory;
  }

  public static CompressionStrategy getCompressionStrategy(String compressionHeader) {
    if (compressionHeader == null) {
      return CompressionStrategy.NO_OP;
    }
    return CompressionStrategy.valueOf(Integer.parseInt(compressionHeader));
  }

  public boolean canPassThroughResponse(CompressionStrategy responseCompression) {
    return responseCompression == clientCompression || responseCompression == CompressionStrategy.NO_OP;
  }

  public ContentDecompressResult decompressSingleGetContent(CompressionStrategy compressionStrategy, ByteBuf content) {
    if (canPassThroughResponse(compressionStrategy)) {
      // Decompress record on the client side if needed
      return new ContentDecompressResult(content, compressionStrategy, 0);
    }

    AggRouterHttpRequestStats stats = routerStats.getStatsByType(RequestType.SINGLE_GET);
    stats.recordCompressedResponseSize(this.storeVersionName.getStoreName(), content.readableBytes());
    long startTimeInNs = System.nanoTime();
    ByteBuf copy = content.isReadOnly() ? content.copy() : content;
    ByteBuf decompressedData =
        Unpooled.wrappedBuffer(decompressRecord(compressionStrategy, copy.nioBuffer(), RequestType.SINGLE_GET));
    final long decompressionTimeInNs = System.nanoTime() - startTimeInNs;
    stats.recordDecompressionTime(
        this.storeVersionName.getStoreName(),
        LatencyUtils.getElapsedTimeFromNSToMS(startTimeInNs));

    /**
     * When using compression, the data in response is already copied to `decompressedData`, so we can explicitly
     * release the ByteBuf in the response immediately to avoid any memory leak.
     *
     * When not using compression, the backing byte array in the response will be reused to construct the response to
     * client, and the ByteBuf will be released in the netty pipeline.
     */
    content.release();

    return new ContentDecompressResult(decompressedData, CompressionStrategy.NO_OP, decompressionTimeInNs);
  }

  /**
   * Decompressing multi-get response in router side is a bit of overhead.
   * Since records could be concatenated within one response, we need to
   * deserialize the records; decompress the records and then serialize
   * them back.
   */
  public ContentDecompressResult decompressMultiGetContent(CompressionStrategy compressionStrategy, ByteBuf content) {
    if (canPassThroughResponse(compressionStrategy)) {
      return new ContentDecompressResult(content, compressionStrategy, 0L);
    } else {
      ByteBuf output;
      long startTimeInNs = System.nanoTime();
      if (content instanceof CompositeByteBuf) {
        CompositeByteBuf compositeInput = (CompositeByteBuf) content;
        switch (compositeInput.numComponents()) {
          case 0:
            output = Unpooled.EMPTY_BUFFER;
            break;
          case 1:
            output = decompressMultiGetRecords(compressionStrategy, compositeInput.component(0), MULTI_GET);
            break;
          default:
            output = Unpooled.compositeBuffer(compositeInput.numComponents());
            CompositeByteBuf compositeOutput = (CompositeByteBuf) output;
            for (ByteBuf buffer: compositeInput) {
              compositeOutput.addComponent(true, decompressMultiGetRecords(compressionStrategy, buffer, MULTI_GET));
            }
        }
      } else {
        output = decompressMultiGetRecords(compressionStrategy, content, MULTI_GET);
      }
      final long decompressionTimeInNs = System.nanoTime() - startTimeInNs;
      /**
       * When using compression, the data in response is already copied during decompression, so we can explicitly
       * release the ByteBuf in the response immediately to avoid any memory leak.
       *
       * When not using compression, the backing byte array in the response will be reused to construct the response to
       * client, and the ByteBuf will be released in the netty pipeline.
       */
      content.release();

      return new ContentDecompressResult(output, CompressionStrategy.NO_OP, decompressionTimeInNs);
    }
  }

  public Pair<ByteBuf, CompressionStrategy> processMultiGetResponseForStreaming(
      CompressionStrategy responseCompression,
      ByteBuf content) {
    if (canPassThroughResponse(responseCompression)) {
      // Decompress record on the client side if needed
      return new Pair<>(content, responseCompression);
    }

    AggRouterHttpRequestStats stats = routerStats.getStatsByType(MULTI_GET_STREAMING);
    stats.recordCompressedResponseSize(this.storeVersionName.getStoreName(), content.readableBytes());
    long startTimeInNs = System.nanoTime();
    ByteBuf copy = content.isReadOnly() ? content.copy() : content;
    ByteBuf decompressedContent = decompressMultiGetRecords(responseCompression, copy, MULTI_GET_STREAMING);
    stats.recordDecompressionTime(
        this.storeVersionName.getStoreName(),
        LatencyUtils.getElapsedTimeFromNSToMS(startTimeInNs));
    content.release();
    return new Pair<>(decompressedContent, CompressionStrategy.NO_OP);
  }

  private ByteBuffer decompressRecord(
      CompressionStrategy compressionStrategy,
      ByteBuffer compressedData,
      RequestType requestType) {
    try {
      return getCompressor(compressionStrategy, requestType).decompress(compressedData);
    } catch (IOException e) {
      String errorMsg = String.format(
          "Failed to decompress data. Store: %s; Version: %d, error: %s",
          this.storeVersionName.getStoreName(),
          this.storeVersionName.getVersionNumber(),
          e.getMessage());
      throw RouterExceptionAndTrackingUtils
          .newVeniceExceptionAndTracking(this.storeVersionName.getStoreName(), requestType, BAD_GATEWAY, errorMsg);
    }
  }

  private ByteBuf decompressMultiGetRecords(
      CompressionStrategy compressionStrategy,
      ByteBuf data,
      RequestType requestType) {
    ByteBuf copy = data.isReadOnly() ? data.copy() : data;
    Iterable<MultiGetResponseRecordV1> records = recordDeserializer.deserializeObjects(
        OptimizedBinaryDecoderFactory.defaultFactory()
            .createOptimizedBinaryDecoder(copy.array(), 0, copy.readableBytes()));

    try {
      VeniceCompressor compressor = getCompressor(compressionStrategy, requestType);
      for (MultiGetResponseRecordV1 record: records) {
        record.value = compressor.decompress(record.value);
      }
    } catch (IOException e) {
      String errorMsg = String.format(
          "Failed to decompress data. Store: %s; Version: %d, error: %s",
          this.storeVersionName.getStoreName(),
          this.storeVersionName.getVersionNumber(),
          e.getMessage());
      throw RouterExceptionAndTrackingUtils
          .newVeniceExceptionAndTracking(this.storeVersionName.getStoreName(), requestType, BAD_GATEWAY, errorMsg);
    }

    return Unpooled.wrappedBuffer(recordSerializer.serializeObjects(records));
  }

  private VeniceCompressor getCompressor(CompressionStrategy compressionStrategy, RequestType requestType) {
    VeniceCompressor compressor;
    if (compressionStrategy == CompressionStrategy.ZSTD_WITH_DICT) {
      compressor = compressorFactory.getVersionSpecificCompressor(this.storeVersionName.getName());
      if (compressor == null) {
        throw RouterExceptionAndTrackingUtils.newVeniceExceptionAndTracking(
            this.storeVersionName.getStoreName(),
            requestType,
            SERVICE_UNAVAILABLE,
            "Compressor not available for resource " + this.storeVersionName.getName()
                + ". Dictionary not downloaded.");
      }
    } else {
      compressor = compressorFactory.getCompressor(compressionStrategy);
    }
    return compressor;
  }
}
