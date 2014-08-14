/*
 * *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.apache.tez.dag.api.client;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.Set;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.Vertex;

@InterfaceAudience.Private
public class MRDAGClient extends DAGClient {

  private final DAGClient realClient;

  public MRDAGClient(DAGClient dagClient) {
    this.realClient = dagClient;
  }

  @Override
  public ApplicationId getApplicationId() {
    return realClient.getApplicationId();
  }

  @Override
  protected ApplicationReport getApplicationReportInternal() {
    return realClient.getApplicationReportInternal();
  }

  public ApplicationReport getApplicationReport() {
    return getApplicationReportInternal();
  }

  @Override
  public DAGStatus getDAGStatus(
      Set<StatusGetOpts> statusOptions) throws IOException, TezException {
    return realClient.getDAGStatus(statusOptions);
  }

  @Override
  public VertexStatus getVertexStatus(String vertexName,
                                      Set<StatusGetOpts> statusOptions) throws IOException,
      TezException {
    return realClient.getVertexStatus(vertexName, statusOptions);
  }

  @Override
  public void tryKillDAG() throws IOException, TezException {
    realClient.tryKillDAG();
  }

  @Override
  public DAGStatus waitForCompletion() throws IOException, TezException, InterruptedException {
    return realClient.waitForCompletion();
  }

  @Override
  public DAGStatus waitForCompletionWithStatusUpdates(
      @Nullable Set<StatusGetOpts> statusGetOpts) throws IOException, TezException, InterruptedException {
    return realClient.waitForCompletionWithStatusUpdates(statusGetOpts);
  }

  @Override
  public void close() throws IOException {
    realClient.close();
  }
}