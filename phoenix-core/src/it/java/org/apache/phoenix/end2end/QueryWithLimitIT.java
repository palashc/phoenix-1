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
package org.apache.phoenix.end2end;

import static org.apache.phoenix.query.QueryServicesOptions.UNLIMITED_QUEUE_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.RejectedExecutionException;
import org.apache.phoenix.compile.ExplainPlan;
import org.apache.phoenix.compile.ExplainPlanAttributes;
import org.apache.phoenix.jdbc.PhoenixPreparedStatement;
import org.apache.phoenix.query.BaseTest;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.TestUtil;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.phoenix.thirdparty.com.google.common.collect.Maps;

@Category(NeedsOwnMiniClusterTest.class)
public class QueryWithLimitIT extends BaseTest {

  private String tableName;
  private static Map<String, String> props = Maps.newHashMapWithExpectedSize(5);

  @BeforeClass
  public static synchronized void doSetup() throws Exception {
    // Must update config before starting server
    props.put(QueryServices.STATS_GUIDEPOST_WIDTH_BYTES_ATTRIB, Long.toString(50));
    props.put(QueryServices.QUEUE_SIZE_ATTRIB, Integer.toString(1));
    props.put(QueryServices.SEQUENCE_SALT_BUCKETS_ATTRIB, Integer.toString(0)); // Prevents
                                                                                // RejectedExecutionException
                                                                                // when creatomg
                                                                                // sequence table
    props.put(QueryServices.THREAD_POOL_SIZE_ATTRIB, Integer.toString(4));
    props.put(QueryServices.LOG_SALT_BUCKETS_ATTRIB, Integer.toString(0)); // Prevents
                                                                           // RejectedExecutionException
                                                                           // when creating log
                                                                           // table
  }

  @Before
  public void setupDriver() throws Exception {
    destroyDriver();
    setUpTestDriver(new ReadOnlyProps(props.entrySet().iterator()));
    tableName = generateUniqueName();
  }

  @Test
  public void testQueryWithLimitAndStats() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      conn.createStatement().execute(
        "create table " + tableName + "\n" + "   (i1 integer not null, i2 integer not null\n"
          + "    CONSTRAINT pk PRIMARY KEY (i1,i2))");
      initTableValues(conn, 100);

      String query = "SELECT i1 FROM " + tableName + " LIMIT 1";
      ResultSet rs = conn.createStatement().executeQuery(query);
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertFalse(rs.next());

      ExplainPlan plan = conn.prepareStatement(query).unwrap(PhoenixPreparedStatement.class)
        .optimizeQuery().getExplainPlan();
      ExplainPlanAttributes explainPlanAttributes = plan.getPlanStepsAsAttributes();
      assertEquals("SERIAL 1-WAY", explainPlanAttributes.getIteratorTypeAndScanSize());
      assertEquals("FULL SCAN ", explainPlanAttributes.getExplainScanType());
      assertEquals(tableName, explainPlanAttributes.getTableName());
      assertEquals("SERVER FILTER BY FIRST KEY ONLY", explainPlanAttributes.getServerWhereFilter());
      assertEquals(1, explainPlanAttributes.getServerRowLimit().intValue());
      assertEquals(1, explainPlanAttributes.getClientRowLimit().intValue());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testQueryWithoutLimitFails() throws Exception {
    Properties connProps = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    String query = "SELECT i1 FROM " + tableName;
    try (Connection conn = DriverManager.getConnection(getUrl(), connProps)) {

      conn.createStatement().execute(
        "create table " + tableName + "\n" + "   (i1 integer not null, i2 integer not null\n"
          + "    CONSTRAINT pk PRIMARY KEY (i1,i2))");
      initTableValues(conn, 100);
      conn.createStatement().execute("UPDATE STATISTICS " + tableName);

      try {
        ResultSet rs = conn.createStatement().executeQuery(query);
        rs.next();
        fail();
      } catch (SQLException e) {
        assertTrue(e.getCause() instanceof RejectedExecutionException);
      }
    }

    // now run the same test with queue size set to unlimited
    try {
      destroyDriver();
      // copy the existing properties
      Map<String, String> newProps = Maps.newHashMap(props);
      newProps.put(QueryServices.QUEUE_SIZE_ATTRIB, Integer.toString(UNLIMITED_QUEUE_SIZE));
      setUpTestDriver(new ReadOnlyProps(newProps.entrySet().iterator()));
      try (Connection conn = DriverManager.getConnection(getUrl(), connProps)) {
        // now the query should succeed
        ResultSet rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
      }
    } finally {
      destroyDriver();
    }
  }

  protected void initTableValues(Connection conn, int nRows) throws Exception {
    PreparedStatement stmt = conn.prepareStatement("upsert into " + tableName + " VALUES (?, ?)");
    for (int i = 0; i < nRows; i++) {
      stmt.setInt(1, i);
      stmt.setInt(2, i + 1);
      stmt.execute();
    }

    conn.commit();
  }

}
