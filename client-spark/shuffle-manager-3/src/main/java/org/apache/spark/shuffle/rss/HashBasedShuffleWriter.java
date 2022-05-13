/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.rss;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

import scala.Option;
import scala.Product2;
import scala.reflect.ClassTag$;

import com.google.common.annotations.VisibleForTesting;
import org.apache.spark.Partitioner;
import org.apache.spark.ShuffleDependency;
import org.apache.spark.SparkEnv;
import org.apache.spark.TaskContext;
import org.apache.spark.annotation.Private;
import org.apache.spark.scheduler.MapStatus;
import org.apache.spark.serializer.SerializationStream;
import org.apache.spark.serializer.SerializerInstance;
import org.apache.spark.shuffle.ShuffleWriteMetricsReporter;
import org.apache.spark.shuffle.ShuffleWriter;
import org.apache.spark.sql.catalyst.expressions.UnsafeRow;
import org.apache.spark.sql.execution.PartitionIdPassthrough;
import org.apache.spark.sql.execution.UnsafeRowSerializer;
import org.apache.spark.sql.execution.metric.SQLMetric;
import org.apache.spark.storage.BlockManagerId;
import org.apache.spark.unsafe.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aliyun.emr.rss.client.ShuffleClient;
import com.aliyun.emr.rss.client.write.DataPusher;
import com.aliyun.emr.rss.common.RssConf;

@Private
public class HashBasedShuffleWriter<K, V, C> extends ShuffleWriter<K, V> {

  private static final Logger logger = LoggerFactory.getLogger(HashBasedShuffleWriter.class);

  private static final int DEFAULT_INITIAL_SER_BUFFER_SIZE = 1024 * 1024;

  private final int SEND_BUFFER_SIZE;
  private final ShuffleDependency<K, V, C> dep;
  private final Partitioner partitioner;
  private final ShuffleWriteMetricsReporter writeMetrics;
  private final String appId;
  private final int shuffleId;
  private final int mapId;
  private final TaskContext taskContext;
  private final ShuffleClient rssShuffleClient;
  private final int numMappers;
  private final int numPartitions;

  @Nullable
  private MapStatus mapStatus;
  private long peakMemoryUsedBytes = 0;

  /**
   * Subclass of ByteArrayOutputStream that exposes `buf` directly.
   */
  private static final class MyByteArrayOutputStream extends ByteArrayOutputStream {
    MyByteArrayOutputStream(int size) {
      super(size);
    }

    public byte[] getBuf() {
      return buf;
    }
  }

  private final MyByteArrayOutputStream serBuffer;
  private final SerializationStream serOutputStream;

  private byte[][] sendBuffers;
  private int[] sendOffsets;

  private final LongAdder[] mapStatusLengths;
  private final long[] tmpRecords;

  /**
   * Are we in the process of stopping? Because map tasks can call stop() with success = true
   * and then call stop() with success = false if they get an exception, we want to make sure
   * we don't try deleting files, etc twice.
   */
  private volatile boolean stopping = false;

  private final DataPusher dataPusher;

  // In order to facilitate the writing of unit test code, ShuffleClient needs to be passed in as
  // parameters. By the way, simplify the passed parameters.
  public HashBasedShuffleWriter(
      RssShuffleHandle<K, V, C> handle,
      TaskContext taskContext,
      RssConf conf,
      ShuffleClient client,
      ShuffleWriteMetricsReporter metrics
  ) throws IOException {
    this.mapId = taskContext.partitionId();
    this.dep = handle.dependency();
    this.appId = handle.newAppId();
    this.shuffleId = dep.shuffleId();
    SerializerInstance serializer = dep.serializer().newInstance();
    this.partitioner = dep.partitioner();
    this.writeMetrics = metrics;
    this.taskContext = taskContext;
    this.numMappers = handle.numMappers();
    this.numPartitions = dep.partitioner().numPartitions();
    this.rssShuffleClient = client;

    serBuffer = new MyByteArrayOutputStream(DEFAULT_INITIAL_SER_BUFFER_SIZE);
    serOutputStream = serializer.serializeStream(serBuffer);

    mapStatusLengths = new LongAdder[numPartitions];
    for (int i = 0; i < numPartitions; i++) {
      mapStatusLengths[i]=new LongAdder();
    }
    tmpRecords = new long[numPartitions];

    SEND_BUFFER_SIZE = RssConf.pushDataBufferSize(conf);

    sendBuffers = new byte[numPartitions][];
    sendOffsets = new int[numPartitions];

    dataPusher = new DataPusher(
        appId,
        shuffleId,
        mapId,
        taskContext.attemptNumber(),
        taskContext.taskAttemptId(),
        numMappers,
        numPartitions,
        conf,
        rssShuffleClient,
        writeMetrics::incBytesWritten,
        mapStatusLengths);
  }

  @Override
  public void write(scala.collection.Iterator<Product2<K, V>> records) throws IOException {
    if (canUseFastWrite()) {
      fastWrite0(records);
    } else if (dep.mapSideCombine()) {
      if (dep.aggregator().isEmpty()) {
        throw new UnsupportedOperationException(
            "When using map side combine, an aggregator must be specified.");
      }
      write0(dep.aggregator().get().combineValuesByKey(records, taskContext));
    } else {
      write0(records);
    }
    close();
  }

  @VisibleForTesting
  boolean canUseFastWrite() {
    return dep.serializer() instanceof UnsafeRowSerializer &&
        partitioner instanceof PartitionIdPassthrough;
  }

  private void fastWrite0(scala.collection.Iterator iterator) throws IOException {
    final scala.collection.Iterator<Product2<Integer, UnsafeRow>> records = iterator;

    SQLMetric dataSize = SparkUtils
      .getUnsafeRowSerializerDataSizeMetric((UnsafeRowSerializer) dep.serializer());
    while (records.hasNext()) {
      final Product2<Integer, UnsafeRow> record = records.next();
      final int partitionId = record._1();
      final UnsafeRow row = record._2();

      final int rowSize = row.getSizeInBytes();
      final int serializedRecordSize = 4 + rowSize;

      if (dataSize != null) {
        dataSize.add(rowSize);
      }

      byte[] buffer = sendBuffers[partitionId];
      if (buffer == null) {
        buffer = new byte[SEND_BUFFER_SIZE];
        sendBuffers[partitionId] = buffer;
        peakMemoryUsedBytes += SEND_BUFFER_SIZE;
      }

      if (serializedRecordSize > SEND_BUFFER_SIZE) {
        byte[] giantBuffer = new byte[serializedRecordSize];
        Platform.putInt(giantBuffer, Platform.BYTE_ARRAY_OFFSET, Integer.reverseBytes(rowSize));
        Platform.copyMemory(row.getBaseObject(), row.getBaseOffset(),
            giantBuffer, Platform.BYTE_ARRAY_OFFSET + 4, rowSize);
        pushGiantRecord(partitionId, giantBuffer, serializedRecordSize);
      } else {
        int offset = getOrUpdateOffset(partitionId, buffer, serializedRecordSize);
        Platform.putInt(buffer, Platform.BYTE_ARRAY_OFFSET + offset, Integer.reverseBytes(rowSize));
        Platform.copyMemory(row.getBaseObject(), row.getBaseOffset(),
            buffer, Platform.BYTE_ARRAY_OFFSET + offset + 4, rowSize);
        sendOffsets[partitionId] = offset + serializedRecordSize;
      }
      tmpRecords[partitionId] += 1;
    }
  }

  private void write0(scala.collection.Iterator iterator) throws IOException {
    final scala.collection.Iterator<Product2<K, ?>> records = iterator;

    while (records.hasNext()) {
      final Product2<K, ?> record = records.next();
      final K key = record._1();
      final int partitionId = partitioner.getPartition(key);
      serBuffer.reset();
      serOutputStream.writeKey(key, ClassTag$.MODULE$.apply(key.getClass()));
      serOutputStream.writeValue(record._2(), ClassTag$.MODULE$.apply(record._2().getClass()));
      serOutputStream.flush();

      final int serializedRecordSize = serBuffer.size();
      assert (serializedRecordSize > 0);

      byte[] buffer = getOrCreateBuffer(partitionId);

      if (serializedRecordSize > SEND_BUFFER_SIZE) {
        pushGiantRecord(partitionId, serBuffer.getBuf(), serializedRecordSize);
      } else {
        int offset = getOrUpdateOffset(partitionId, buffer, serializedRecordSize);
        System.arraycopy(serBuffer.getBuf(), 0, buffer, offset, serializedRecordSize);
        sendOffsets[partitionId] = offset + serializedRecordSize;
      }
      tmpRecords[partitionId] += 1;
    }
  }

  private byte[] getOrCreateBuffer(int partitionId) {
    byte[] buffer = sendBuffers[partitionId];
    if (buffer == null) {
      buffer = new byte[SEND_BUFFER_SIZE];
      sendBuffers[partitionId] = buffer;
      peakMemoryUsedBytes += SEND_BUFFER_SIZE;
    }
    return buffer;
  }

  private void pushGiantRecord(int partitionId, byte[] buffer, int numBytes) throws IOException {
    logger.debug("Push giant record, size {}.", numBytes);
    long pushStartTime = System.nanoTime();
    int bytesWritten = rssShuffleClient.pushData(
        appId,
        shuffleId,
        mapId,
        taskContext.attemptNumber(),
        partitionId,
        buffer,
        0,
        numBytes,
        numMappers,
        numPartitions
    );
    mapStatusLengths[partitionId].add(bytesWritten);
    writeMetrics.incBytesWritten(bytesWritten);
    writeMetrics.incWriteTime(System.nanoTime() - pushStartTime);
  }

  private int getOrUpdateOffset(
      int partitionId, byte[] buffer, int serializedRecordSize) throws IOException {
    int offset = sendOffsets[partitionId];
    if ((SEND_BUFFER_SIZE - offset) < serializedRecordSize) {
      flushSendBuffer(partitionId, buffer, offset);
      updateMapStatus();
      offset = 0;
    }
    return offset;
  }

  private void flushSendBuffer(int partitionId, byte[] buffer, int size) throws IOException {
    long pushStartTime = System.nanoTime();
    logger.debug("Flush buffer, size {}.", size);
    dataPusher.addTask(partitionId, buffer, size);
    writeMetrics.incWriteTime(System.nanoTime() - pushStartTime);
  }

  private void close() throws IOException {
    // here we wait for all the in-flight batches to return which sent by dataPusher thread
    long pushMergedDataTime = System.nanoTime();
    dataPusher.waitOnTermination();
    rssShuffleClient.prepareForMergeData(shuffleId, mapId, taskContext.attemptNumber());

    // merge and push residual data to reduce network traffic
    // NB: since dataPusher thread have no in-flight data at this point,
    //     we now push merged data by task thread will not introduce any contention
    for (int i = 0; i < sendBuffers.length; i++) {
      final int size = sendOffsets[i];
      if (size > 0) {
        int bytesWritten = rssShuffleClient.mergeData(
            appId,
            shuffleId,
            mapId,
            taskContext.attemptNumber(),
            i,
            sendBuffers[i],
            0,
            size,
            numMappers,
            numPartitions
        );
        // free buffer
        sendBuffers[i] = null;
        mapStatusLengths[i].add(bytesWritten);
        writeMetrics.incBytesWritten(bytesWritten);
      }
    }
    rssShuffleClient.pushMergedData(appId, shuffleId, mapId, taskContext.attemptNumber());
    writeMetrics.incWriteTime(System.nanoTime() - pushMergedDataTime);

    updateMapStatus();

    sendBuffers = null;
    sendOffsets = null;

    long waitStartTime = System.nanoTime();
    rssShuffleClient.mapperEnd(appId, shuffleId, mapId, taskContext
        .attemptNumber(), numMappers);
    writeMetrics.incWriteTime(System.nanoTime() - waitStartTime);

    BlockManagerId bmId = SparkEnv.get().blockManager().shuffleServerId();
    mapStatus = SparkUtils.createMapStatus(bmId, SparkUtils.unwrap(mapStatusLengths),
      taskContext.taskAttemptId());
  }

  private void updateMapStatus() {
    long recordsWritten = 0;
    for (int i = 0; i < partitioner.numPartitions(); i++) {
      recordsWritten += tmpRecords[i];
      tmpRecords[i] = 0;
    }
    writeMetrics.incRecordsWritten(recordsWritten);
  }

  @Override
  public Option<MapStatus> stop(boolean success) {
    try {
      taskContext.taskMetrics().incPeakExecutionMemory(peakMemoryUsedBytes);

      if (stopping) {
        return Option.apply(null);
      } else {
        stopping = true;
        if (success) {
          if (mapStatus == null) {
            throw new IllegalStateException("Cannot call stop(true) without having called write()");
          }
          return Option.apply(mapStatus);
        } else {
          return Option.apply(null);
        }
      }
    } finally {
      rssShuffleClient.cleanup(appId, shuffleId, mapId, taskContext.attemptNumber());
    }
  }

  public long[] getPartitionLengths() {
    throw new UnsupportedOperationException(
      "RSS is not compatible with Spark push mode, please set spark.shuffle.push.enabled to false");
  }
}

