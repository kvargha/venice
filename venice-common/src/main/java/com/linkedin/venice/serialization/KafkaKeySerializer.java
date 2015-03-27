package com.linkedin.venice.serialization;

import com.linkedin.venice.exceptions.VeniceMessageException;
import com.linkedin.venice.message.ControlFlagKafkaKey;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.message.OperationType;
import kafka.utils.VerifiableProperties;
import org.apache.log4j.Logger;

import java.io.*;


/**
 * Serializer to encode/decode KafkaKey for Venice customized kafka message
 * Used by Kafka to convert to/from byte arrays.
 *
 * KafkaKey Schema (in order)
 * - Operation Type - Either WRITE, PARTIAL_WRITE, BEGIN_OF_PUSH, END_OF_PUSH
 * - Payload (Key Object)
 *
 */
public class KafkaKeySerializer implements Serializer<KafkaKey> {

  static final Logger logger = Logger.getLogger(KafkaKeySerializer.class.getName()); // log4j logger

  public KafkaKeySerializer(VerifiableProperties verifiableProperties) {
        /* This constructor is not used, but is required for compilation */
  }

  @Override
  /**
   * Converts from a byte[] to a KafkaKey
   * @param bytes - byte[] to be converted
   * @return Converted Venice Message
   * */
  public KafkaKey fromBytes(byte[] bytes) {

    byte opTypeByte;
    OperationType opType = null;
    int jobId = -1;
    byte[] key = null;

    ByteArrayInputStream bytesIn = null;
    ObjectInputStream objectInputStream = null;

    try {

      bytesIn = new ByteArrayInputStream(bytes);
      objectInputStream = new ObjectInputStream(bytesIn);

      /* read opTypeByte and validate Venice message */
      opTypeByte = objectInputStream.readByte();
      opType = OperationType.getOperationType(opTypeByte);

      /* read job Id if optype is BEGIN_OF_PUSH or END_OF_PUSH */
      if(opType == OperationType.BEGIN_OF_PUSH || opType == OperationType.END_OF_PUSH){
        jobId = objectInputStream.readInt();
      }

      /* read payload, one byte at a time */
      int byteCount = objectInputStream.available();
      key = new byte[byteCount];
      for (int i = 0; i < byteCount; i++) {
        key[i] = objectInputStream.readByte();
      }
    } catch (VeniceMessageException e) {
      String errorMessage = "Error occurred during deserialization of KafkaKey";
      logger.error(errorMessage, e);
      throw e;

    } catch (IOException e) {
      logger.error("IOException while converting byte[] to KafkaKey: ", e);
      // TODO what should be done here?
    } finally {

      // safely close the input/output streams
      try {
        objectInputStream.close();
      } catch (IOException e) {
        logger.error("IOException while closing the input stream", e);
      }

      try {
        bytesIn.close();
      } catch (IOException e) {
        logger.error("IOException while closing the input stream", e);
      }
    }
    if(opType == OperationType.BEGIN_OF_PUSH || opType == OperationType.END_OF_PUSH){
        return new ControlFlagKafkaKey(opType, key, jobId);
    }
    return new KafkaKey(opType, key);
  }

  @Override
  /**
   * Converts from a KafkaKey to a byte[]
   * @param kafkaKey - KafkaKey to be converted
   * @return Converted byte[]
   * */
  public byte[] toBytes(KafkaKey kafkaKey) {

    ByteArrayOutputStream bytesOut = null;
    ObjectOutputStream objectOutputStream = null;
    byte[] bytes = null;

    try {

      bytesOut = new ByteArrayOutputStream();
      objectOutputStream = new ObjectOutputStream(bytesOut);

      OperationType opType = kafkaKey.getOperationType();
      ControlFlagKafkaKey controlFlagKafkaKey = null;
      if(opType == OperationType.BEGIN_OF_PUSH || opType == OperationType.END_OF_PUSH){
        controlFlagKafkaKey = (ControlFlagKafkaKey) kafkaKey;
      }

      /* write Operation Type byte */
      objectOutputStream.writeByte(OperationType.getByteCode(opType));

      /* Write jobID if its a control message */
      if(opType == OperationType.END_OF_PUSH || opType == OperationType.END_OF_PUSH){
          objectOutputStream.writeInt(controlFlagKafkaKey.getJobId());
      }

      /* write payload */
      objectOutputStream.write(kafkaKey.getKey());
      objectOutputStream.flush();

      bytes = bytesOut.toByteArray();
    } catch (IOException e) {
      logger.error("Could not serialize KafkaKey: " + kafkaKey.getKey());
        // TODO what should be done here?
    } finally {

      // safely close the input/output streams
      try {
        objectOutputStream.close();
      } catch (IOException e) {}

      try {
        bytesOut.close();
      } catch (IOException e) {}

      return bytes;
    }
  }
}
