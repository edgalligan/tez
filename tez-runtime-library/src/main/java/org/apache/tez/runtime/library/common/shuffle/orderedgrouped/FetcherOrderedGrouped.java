/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tez.runtime.library.common.shuffle.orderedgrouped;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.tez.http.BaseHttpConnection;
import org.apache.tez.http.HttpConnectionParams;
import org.apache.tez.common.CallableWithNdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.tez.common.TezRuntimeFrameworkConfigs;
import org.apache.tez.common.counters.TezCounter;
import org.apache.tez.common.security.JobTokenSecretManager;
import org.apache.tez.runtime.library.common.Constants;
import org.apache.tez.runtime.library.common.InputAttemptIdentifier;
import org.apache.tez.runtime.library.common.shuffle.orderedgrouped.MapOutput.Type;
import org.apache.tez.runtime.library.common.sort.impl.TezIndexRecord;
import org.apache.tez.runtime.library.common.sort.impl.TezSpillRecord;
import org.apache.tez.runtime.library.exceptions.FetcherReadTimeoutException;
import org.apache.tez.runtime.library.common.shuffle.ShuffleUtils;

import com.google.common.annotations.VisibleForTesting;

class FetcherOrderedGrouped extends CallableWithNdc<Void> {
  
  private static final Logger LOG = LoggerFactory.getLogger(FetcherOrderedGrouped.class);

  private static final AtomicInteger nextId = new AtomicInteger(0);

  private final Configuration conf;
  private final boolean localDiskFetchEnabled;

  private final TezCounter connectionErrs;
  private final TezCounter ioErrs;
  private final TezCounter wrongLengthErrs;
  private final TezCounter badIdErrs;
  private final TezCounter wrongMapErrs;
  private final TezCounter wrongReduceErrs;
  private final FetchedInputAllocatorOrderedGrouped allocator;
  private final ShuffleScheduler scheduler;
  private final ShuffleClientMetrics metrics;
  private final Shuffle shuffle;
  private final int id;
  private final String logIdentifier;
  private final String localShuffleHostPort;
  private final MapHost mapHost;

  private final int currentPartition;

  // Decompression of map-outputs
  private final CompressionCodec codec;
  private final JobTokenSecretManager jobTokenSecretManager;

  final HttpConnectionParams httpConnectionParams;

  @VisibleForTesting
  volatile boolean stopped = false;

  private final boolean ifileReadAhead;
  private final int ifileReadAheadLength;
  @VisibleForTesting
  LinkedList<InputAttemptIdentifier> remaining;

  volatile DataInputStream input;

  volatile BaseHttpConnection httpConnection;
  private final boolean asyncHttp;


  // Initiative value is 0, which means it hasn't retried yet.
  private long retryStartTime = 0;

  public FetcherOrderedGrouped(HttpConnectionParams httpConnectionParams,
                               ShuffleScheduler scheduler,
                               FetchedInputAllocatorOrderedGrouped allocator,
                               ShuffleClientMetrics metrics,
                               Shuffle shuffle, JobTokenSecretManager jobTokenSecretMgr,
                               boolean ifileReadAhead, int ifileReadAheadLength,
                               CompressionCodec codec,
                               Configuration conf,
                               boolean localDiskFetchEnabled,
                               String localHostname,
                               int shufflePort,
                               String srcNameTrimmed,
                               MapHost mapHost,
                               TezCounter ioErrsCounter,
                               TezCounter wrongLengthErrsCounter,
                               TezCounter badIdErrsCounter,
                               TezCounter wrongMapErrsCounter,
                               TezCounter connectionErrsCounter,
                               TezCounter wrongReduceErrsCounter,
                               boolean asyncHttp) {
    this.scheduler = scheduler;
    this.allocator = allocator;
    this.metrics = metrics;
    this.shuffle = shuffle;
    this.mapHost = mapHost;
    this.currentPartition = this.mapHost.getPartitionId();
    this.id = nextId.incrementAndGet();
    this.jobTokenSecretManager = jobTokenSecretMgr;

    this.ioErrs = ioErrsCounter;
    this.wrongLengthErrs = wrongLengthErrsCounter;
    this.badIdErrs = badIdErrsCounter;
    this.wrongMapErrs = wrongMapErrsCounter;
    this.connectionErrs = connectionErrsCounter;
    this.wrongReduceErrs = wrongReduceErrsCounter;

    this.ifileReadAhead = ifileReadAhead;
    this.ifileReadAheadLength = ifileReadAheadLength;
    this.httpConnectionParams = httpConnectionParams;
    this.asyncHttp = asyncHttp;
    if (codec != null) {
      this.codec = codec;
    } else {
      this.codec = null;
    }
    this.conf = conf;
    this.localShuffleHostPort = localHostname + ":" + String.valueOf(shufflePort);

    this.localDiskFetchEnabled = localDiskFetchEnabled;

    this.logIdentifier = "fetcher [" + srcNameTrimmed + "] #" + id;
  }

  @VisibleForTesting
  protected void fetchNext() throws InterruptedException, IOException {
    try {
      metrics.threadBusy();

      String hostPort = mapHost.getHostIdentifier();
      if (localDiskFetchEnabled && hostPort.equals(localShuffleHostPort)) {
        setupLocalDiskFetch(mapHost);
      } else {
        // Shuffle
        copyFromHost(mapHost);
      }
    } finally {
      cleanupCurrentConnection(false);
      scheduler.freeHost(mapHost);
      metrics.threadFree();
    }
  }

  @Override
  public Void callInternal() {
    try {
      remaining = null; // Safety.
      fetchNext();
    } catch (InterruptedException ie) {
      //TODO: might not be respected when fetcher is in progress / server is busy.  TEZ-711
      //Set the status back
      Thread.currentThread().interrupt();
      return null;
    } catch (Throwable t) {
      shuffle.reportException(t);
      // Shuffle knows how to deal with failures post shutdown via the onFailure hook
    }
    return null;
  }

  public void shutDown() {
    if (!stopped) {
      stopped = true;
      // An interrupt will come in while shutting down the thread.
      cleanupCurrentConnection(false);
    }
  }

  private final Object cleanupLock = new Object();
  private void cleanupCurrentConnection(boolean disconnect) {
    // Synchronizing on cleanupLock to ensure we don't run into a parallel close
    // Can't synchronize on the main class itself since that would cause the
    // shutdown request to block
    synchronized (cleanupLock) {
      try {
        if (httpConnection != null) {
          httpConnection.cleanup(disconnect);
          httpConnection = null;
        }
      } catch (IOException e) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Exception while shutting down fetcher " + logIdentifier, e);
        } else {
          LOG.info("Exception while shutting down fetcher " + logIdentifier + ": " + e.getMessage());
        }
      }
    }
  }

  /**
   * The crux of the matter...
   * 
   * @param host {@link MapHost} from which we need to  
   *              shuffle available map-outputs.
   */
  @VisibleForTesting
  protected void copyFromHost(MapHost host) throws IOException {
    // reset retryStartTime for a new host
    retryStartTime = 0;
    // Get completed maps on 'host'
    List<InputAttemptIdentifier> srcAttempts = scheduler.getMapsForHost(host);

    // Sanity check to catch hosts with only 'OBSOLETE' maps, 
    // especially at the tail of large jobs
    if (srcAttempts.size() == 0) {
      return;
    }
    
    if(LOG.isDebugEnabled()) {
      LOG.debug("Fetcher " + id + " going to fetch from " + host + " for: "
        + srcAttempts + ", partitionId: " + currentPartition);
    }
    
    // List of maps to be fetched yet
    remaining = new LinkedList<InputAttemptIdentifier>(srcAttempts);
    
    // Construct the url and connect

    try {
      if (!setupConnection(host, srcAttempts)) {
        if (stopped) {
          cleanupCurrentConnection(true);
        }
        // Maps will be added back in the finally block in case of failure.
        return;
      }

      // Loop through available map-outputs and fetch them
      // On any error, faildTasks is not null and we exit
      // after putting back the remaining maps to the 
      // yet_to_be_fetched list and marking the failed tasks.
      InputAttemptIdentifier[] failedTasks = null;
      while (!remaining.isEmpty() && failedTasks == null) {
        // fail immediately after first failure because we dont know how much to
        // skip for this error in the input stream. So we cannot move on to the 
        // remaining outputs. YARN-1773. Will get to them in the next retry.
        try {
          failedTasks = copyMapOutput(host, input);
        } catch (FetcherReadTimeoutException e) {
          // Setup connection again if disconnected
          cleanupCurrentConnection(true);
          if (stopped) {
            LOG.info("Not re-establishing connection since Fetcher has been stopped");
            return;
          }
          // Connect with retry
          if (!setupConnection(host, new LinkedList<InputAttemptIdentifier>(remaining))) {
            if (stopped) {
              cleanupCurrentConnection(true);
              LOG.info("Not reporting connection re-establishment failure since fetcher is stopped");
              return;
            }
            failedTasks = new InputAttemptIdentifier[] {getNextRemainingAttempt()};
            break;
          }
        }
      }

      if (failedTasks != null && failedTasks.length > 0) {
        if (stopped) {
          LOG.info("Ignoring copyMapOutput failures for tasks: " + Arrays.toString(failedTasks) +
              " since Fetcher has been stopped");
        } else {
          LOG.warn("copyMapOutput failed for tasks " + Arrays.toString(failedTasks));
          for (InputAttemptIdentifier left : failedTasks) {
            scheduler.copyFailed(left, host, true, false);
          }
        }
      }

      cleanupCurrentConnection(false);

      // Sanity check
      if (failedTasks == null && !remaining.isEmpty()) {
        throw new IOException("server didn't return all expected map outputs: "
            + remaining.size() + " left.");
      }
    } finally {
      putBackRemainingMapOutputs(host);
    }
  }

  @VisibleForTesting
  boolean setupConnection(MapHost host, List<InputAttemptIdentifier> attempts)
      throws IOException {
    boolean connectSucceeded = false;
    try {
      URL url = ShuffleUtils.constructInputURL(host.getBaseUrl(), attempts,
          httpConnectionParams.isKeepAlive());
      httpConnection = ShuffleUtils.getHttpConnection(asyncHttp, url, httpConnectionParams,
          logIdentifier, jobTokenSecretManager);
      connectSucceeded = httpConnection.connect();

      if (stopped) {
        LOG.info("Detected fetcher has been shutdown after connection establishment. Returning");
        return false;
      }
      input = httpConnection.getInputStream();
      httpConnection.validate();
      return true;
    } catch (IOException | InterruptedException ie) {
      if (ie instanceof InterruptedException) {
        Thread.currentThread().interrupt(); //reset status
      }
      if (stopped) {
        LOG.info("Not reporting fetch failure, since an Exception was caught after shutdown");
        return false;
      }
      ioErrs.increment(1);
      if (!connectSucceeded) {
        LOG.warn("Failed to connect to " + host + " with " + remaining.size() + " inputs", ie);
        connectionErrs.increment(1);
      } else {
        LOG.warn("Failed to verify reply after connecting to " + host + " with " + remaining.size()
            + " inputs pending", ie);
      }

      // At this point, either the connection failed, or the initial header verification failed.
      // The error does not relate to any specific Input. Report all of them as failed.
      // This ends up indirectly penalizing the host (multiple failures reported on the single host)
      for(InputAttemptIdentifier left: remaining) {
        // Need to be handling temporary glitches ..
        // Report read error to the AM to trigger source failure heuristics
        scheduler.copyFailed(left, host, connectSucceeded, !connectSucceeded);
      }
      return false;
    }
  }

  @VisibleForTesting
  protected void putBackRemainingMapOutputs(MapHost host) {
    // Cycle through remaining MapOutputs
    boolean isFirst = true;
    InputAttemptIdentifier first = null;
    for (InputAttemptIdentifier left : remaining) {
      if (isFirst) {
        first = left;
        isFirst = false;
        continue;
      }
      scheduler.putBackKnownMapOutput(host, left);
    }
    if (first != null) { // Empty remaining list.
      scheduler.putBackKnownMapOutput(host, first);
    }
  }

  private static InputAttemptIdentifier[] EMPTY_ATTEMPT_ID_ARRAY = new InputAttemptIdentifier[0];

  protected InputAttemptIdentifier[] copyMapOutput(MapHost host,
                                DataInputStream input) throws FetcherReadTimeoutException {
    MapOutput mapOutput = null;
    InputAttemptIdentifier srcAttemptId = null;
    long decompressedLength = -1;
    long compressedLength = -1;
    
    try {
      long startTime = System.currentTimeMillis();
      int forReduce = -1;
      //Read the shuffle header
      try {
        ShuffleHeader header = new ShuffleHeader();
        // TODO Review: Multiple header reads in case of status WAIT ? 
        header.readFields(input);
        if (!header.mapId.startsWith(InputAttemptIdentifier.PATH_PREFIX)) {
          if (!stopped) {
            badIdErrs.increment(1);
            LOG.warn("Invalid map id: " + header.mapId + ", expected to start with " +
                InputAttemptIdentifier.PATH_PREFIX + ", partition: " + header.forReduce);
            return new InputAttemptIdentifier[] {getNextRemainingAttempt()};
          } else {
            LOG.info("Already shutdown. Ignoring invalid map id error");
            return EMPTY_ATTEMPT_ID_ARRAY;
          }
        }
        srcAttemptId = 
            scheduler.getIdentifierForFetchedOutput(header.mapId, header.forReduce);
        compressedLength = header.compressedLength;
        decompressedLength = header.uncompressedLength;
        forReduce = header.forReduce;
      } catch (IllegalArgumentException e) {
        if (!stopped) {
          badIdErrs.increment(1);
          LOG.warn("Invalid map id ", e);
          // Don't know which one was bad, so consider this one bad and dont read
          // the remaining because we dont know where to start reading from. YARN-1773
          return new InputAttemptIdentifier[] {getNextRemainingAttempt()};
        } else {
          LOG.info("Already shutdown. Ignoring invalid map id error. Exception: " +
              e.getClass().getName() + ", Message: " + e.getMessage());
          return EMPTY_ATTEMPT_ID_ARRAY;
        }
      }

      // Do some basic sanity verification
      if (!verifySanity(compressedLength, decompressedLength, forReduce,
          remaining, srcAttemptId)) {
        if (!stopped) {
          if (srcAttemptId == null) {
            LOG.warn("Was expecting " + getNextRemainingAttempt() + " but got null");
            srcAttemptId = getNextRemainingAttempt();
          }
          assert (srcAttemptId != null);
          return new InputAttemptIdentifier[]{srcAttemptId};
        } else {
          LOG.info("Already stopped. Ignoring verification failure.");
          return EMPTY_ATTEMPT_ID_ARRAY;
        }
      }
      
      if(LOG.isDebugEnabled()) {
        LOG.debug("header: " + srcAttemptId + ", len: " + compressedLength + 
            ", decomp len: " + decompressedLength);
      }

      // Get the location for the map output - either in-memory or on-disk
      try {
        mapOutput = allocator.reserve(srcAttemptId, decompressedLength, compressedLength, id);
      } catch (IOException e) {
        if (!stopped) {
          // Kill the reduce attempt
          ioErrs.increment(1);
          scheduler.reportLocalError(e);
        } else {
          LOG.info("Already stopped. Ignoring error from merger.reserve");
        }
        return EMPTY_ATTEMPT_ID_ARRAY;
      }
      
      // Check if we can shuffle *now* ...
      if (mapOutput.getType() == Type.WAIT) {
        LOG.info("fetcher#" + id + " - MergerManager returned Status.WAIT ...");
        //Not an error but wait to process data.
        return EMPTY_ATTEMPT_ID_ARRAY;
      } 
      
      // Go!
      LOG.info("fetcher#" + id + " about to shuffle output of map " + 
               mapOutput.getAttemptIdentifier() + " decomp: " +
               decompressedLength + " len: " + compressedLength + " to " + mapOutput.getType());
      if (mapOutput.getType() == Type.MEMORY) {
        ShuffleUtils.shuffleToMemory(mapOutput.getMemory(), input,
          (int) decompressedLength, (int) compressedLength, codec, ifileReadAhead,
          ifileReadAheadLength, LOG, mapOutput.getAttemptIdentifier().toString());
      } else if (mapOutput.getType() == Type.DISK) {
        ShuffleUtils.shuffleToDisk(mapOutput.getDisk(), host.getHostIdentifier(),
          input, compressedLength, LOG, mapOutput.getAttemptIdentifier().toString());
      } else {
        throw new IOException("Unknown mapOutput type while fetching shuffle data:" +
            mapOutput.getType());
      }

      // Inform the shuffle scheduler
      long endTime = System.currentTimeMillis();
      // Reset retryStartTime as map task make progress if retried before.
      retryStartTime = 0;

      scheduler.copySucceeded(srcAttemptId, host, compressedLength, decompressedLength,
                              endTime - startTime, mapOutput);
      // Note successful shuffle
      remaining.remove(srcAttemptId);
      metrics.successFetch();
      return null;
    } catch (IOException ioe) {
      if (stopped) {
        LOG.info("Not reporting fetch failure for exception during data copy: ["
            + ioe.getClass().getName() + ", " + ioe.getMessage() + "]");
        cleanupCurrentConnection(true);
        if (mapOutput != null) {
          mapOutput.abort(); // Release resources
        }
        // Don't need to put back - since that's handled by the invoker
        return EMPTY_ATTEMPT_ID_ARRAY;
      }
      if (shouldRetry(host, ioe)) {
        //release mem/file handles
        if (mapOutput != null) {
          mapOutput.abort();
        }
        throw new FetcherReadTimeoutException(ioe);
      }
      ioErrs.increment(1);
      if (srcAttemptId == null || mapOutput == null) {
        LOG.info("fetcher#" + id + " failed to read map header" + 
                 srcAttemptId + " decomp: " + 
                 decompressedLength + ", " + compressedLength, ioe);
        if(srcAttemptId == null) {
          return remaining.toArray(new InputAttemptIdentifier[remaining.size()]);
        } else {
          return new InputAttemptIdentifier[] {srcAttemptId};
        }
      }
      
      LOG.warn("Failed to shuffle output of " + srcAttemptId + 
               " from " + host.getHostIdentifier(), ioe); 

      // Inform the shuffle-scheduler
      mapOutput.abort();
      metrics.failedFetch();
      return new InputAttemptIdentifier[] {srcAttemptId};
    }

  }

  /**
   * Check connection needs to be re-established.
   *
   * @param host
   * @param ioe
   * @return true to indicate connection retry. false otherwise.
   * @throws IOException
   */
  private boolean shouldRetry(MapHost host, IOException ioe) {
    if (!(ioe instanceof SocketTimeoutException)) {
      return false;
    }
    // First time to retry.
    long currentTime = System.currentTimeMillis();
    if (retryStartTime == 0) {
      retryStartTime = currentTime;
    }

    if (currentTime - retryStartTime < httpConnectionParams.getReadTimeout()) {
      LOG.warn("Shuffle output from " + host.getHostIdentifier() +
          " failed, retry it.");
      //retry connecting to the host
      return true;
    } else {
      // timeout, prepare to be failed.
      LOG.warn("Timeout for copying MapOutput with retry on host " + host
          + "after " + httpConnectionParams.getReadTimeout() + "milliseconds.");
      return false;
    }
  }
  
  /**
   * Do some basic verification on the input received -- Being defensive
   * @param compressedLength
   * @param decompressedLength
   * @param forReduce
   * @param remaining
   * @param srcAttemptId
   * @return true/false, based on if the verification succeeded or not
   */
  private boolean verifySanity(long compressedLength, long decompressedLength,
      int forReduce, List<InputAttemptIdentifier> remaining, InputAttemptIdentifier srcAttemptId) {
    if (compressedLength < 0 || decompressedLength < 0) {
      wrongLengthErrs.increment(1);
      LOG.warn(logIdentifier + " invalid lengths in map output header: id: " +
          srcAttemptId + " len: " + compressedLength + ", decomp len: " + 
               decompressedLength);
      return false;
    }

    // partitionId verification. Isn't availalbe here because it is encoded into
    // URI
    if (forReduce != currentPartition) {
      wrongReduceErrs.increment(1);
      LOG.warn(logIdentifier + " data for the wrong partition map: " + srcAttemptId + " len: "
          + compressedLength + " decomp len: " + decompressedLength + " for partition " + forReduce
          + ", expected partition: " + currentPartition);
      return false;
    }

    // Sanity check
    if (!remaining.contains(srcAttemptId)) {
      wrongMapErrs.increment(1);
      LOG.warn("Invalid map-output! Received output for " + srcAttemptId);
      return false;
    }
    
    return true;
  }
  
  private InputAttemptIdentifier getNextRemainingAttempt() {
    if (remaining.size() > 0) {
      return remaining.iterator().next();
    } else {
      return null;
    }
  }

  @VisibleForTesting
  protected void setupLocalDiskFetch(MapHost host) throws InterruptedException {
    // Get completed maps on 'host'
    List<InputAttemptIdentifier> srcAttempts = scheduler.getMapsForHost(host);

    // Sanity check to catch hosts with only 'OBSOLETE' maps,
    // especially at the tail of large jobs
    if (srcAttempts.size() == 0) {
      return;
    }

    if(LOG.isDebugEnabled()) {
      LOG.debug("Fetcher " + id + " going to fetch (local disk) from " + host + " for: "
          + srcAttempts + ", partitionId: " + currentPartition);
    }

    // List of maps to be fetched yet
    remaining = new LinkedList<InputAttemptIdentifier>(srcAttempts);

    try {
      final Iterator<InputAttemptIdentifier> iter = remaining.iterator();
      while (iter.hasNext()) {
        // Avoid fetching more if already stopped
        if (stopped) {
          return;
        }
        InputAttemptIdentifier srcAttemptId = iter.next();
        MapOutput mapOutput = null;
        try {
          long startTime = System.currentTimeMillis();
          Path filename = getShuffleInputFileName(srcAttemptId.getPathComponent(), null);

          TezIndexRecord indexRecord = getIndexRecord(srcAttemptId.getPathComponent(),
              currentPartition);

          mapOutput = getMapOutputForDirectDiskFetch(srcAttemptId, filename, indexRecord);
          long endTime = System.currentTimeMillis();
          scheduler.copySucceeded(srcAttemptId, host, indexRecord.getPartLength(),
              indexRecord.getRawLength(), (endTime - startTime), mapOutput);
          iter.remove();
          metrics.successFetch();
        } catch (IOException e) {
          if (mapOutput != null) {
            mapOutput.abort();
          }
          if (!stopped) {
            metrics.failedFetch();
            ioErrs.increment(1);
            scheduler.copyFailed(srcAttemptId, host, true, false);
            LOG.warn("Failed to read local disk output of " + srcAttemptId + " from " +
                host.getHostIdentifier(), e);
          } else {
            LOG.info("Ignoring fetch error during local disk copy since fetcher has already been stopped");
            return;
          }
        }
      }
    } finally {
      putBackRemainingMapOutputs(host);
    }

  }

  @VisibleForTesting
  //TODO: Refactor following to make use of methods from TezTaskOutputFiles to be consistent.
  protected Path getShuffleInputFileName(String pathComponent, String suffix)
      throws IOException {
    LocalDirAllocator localDirAllocator = new LocalDirAllocator(TezRuntimeFrameworkConfigs.LOCAL_DIRS);
    suffix = suffix != null ? suffix : "";

    String pathFromLocalDir = Constants.TEZ_RUNTIME_TASK_OUTPUT_DIR + Path.SEPARATOR +
        pathComponent + Path.SEPARATOR + Constants.TEZ_RUNTIME_TASK_OUTPUT_FILENAME_STRING + suffix;

    return localDirAllocator.getLocalPathToRead(pathFromLocalDir.toString(), conf);
  }

  @VisibleForTesting
  protected TezIndexRecord getIndexRecord(String pathComponent, int partitionId)
      throws IOException {
    Path indexFile = getShuffleInputFileName(pathComponent,
        Constants.TEZ_RUNTIME_TASK_OUTPUT_INDEX_SUFFIX_STRING);
    TezSpillRecord spillRecord = new TezSpillRecord(indexFile, conf);
    return spillRecord.getIndex(partitionId);
  }

  @VisibleForTesting
  protected MapOutput getMapOutputForDirectDiskFetch(InputAttemptIdentifier srcAttemptId,
                                                     Path filename, TezIndexRecord indexRecord)
      throws IOException {
    return MapOutput.createLocalDiskMapOutput(srcAttemptId, allocator, filename,
        indexRecord.getStartOffset(), indexRecord.getPartLength(), true);
  }
}

