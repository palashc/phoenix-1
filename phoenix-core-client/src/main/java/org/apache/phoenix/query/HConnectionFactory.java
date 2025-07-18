/*
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
package org.apache.phoenix.query;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;

/**
 * Factory for creating HConnection
 */
public interface HConnectionFactory {

  /**
   * Creates HConnection to access HBase clusters.
   * @param conf object
   * @return A HConnection instance
   */
  Connection createConnection(Configuration conf) throws IOException;

  /**
   * Creates HConnection to access HBase clusters.
   * @param conf object
   * @param pool object
   * @return A HConnection instance
   */
  default Connection createConnection(Configuration conf, ExecutorService pool) throws IOException {
    return createConnection(conf);
  }

  /**
   * Default implementation. Uses standard HBase HConnections.
   */
  static class HConnectionFactoryImpl implements HConnectionFactory {
    @Override
    public Connection createConnection(Configuration conf) throws IOException {
      return ConnectionFactory.createConnection(conf);
    }

    @Override
    public Connection createConnection(Configuration conf, ExecutorService pool)
      throws IOException {
      return ConnectionFactory.createConnection(conf, pool);
    }
  }
}
