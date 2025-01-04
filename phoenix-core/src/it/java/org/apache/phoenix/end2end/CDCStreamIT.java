/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.phoenix.end2end;

import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.coprocessor.PhoenixMasterObserver;
import org.apache.phoenix.coprocessor.TaskRegionObserver;
import org.apache.phoenix.coprocessorclient.BaseScannerRegionObserverConstants;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.thirdparty.com.google.common.collect.Maps;
import org.apache.phoenix.util.CDCUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_CDC_STREAM_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_CDC_STREAM_STATUS_NAME;
import static org.apache.phoenix.util.CDCUtil.CDC_STREAM_NAME_FORMAT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Category(ParallelStatsDisabledTest.class)
public class CDCStreamIT extends CDCBaseIT {
    private static RegionCoprocessorEnvironment TaskRegionEnvironment;

    @BeforeClass
    public static synchronized void doSetup() throws Exception {
        Map<String, String> props = Maps.newHashMapWithExpectedSize(1);
        props.put(BaseScannerRegionObserverConstants.PHOENIX_MAX_LOOKBACK_AGE_CONF_KEY, Integer.toString(60*60)); // An hour
        props.put(QueryServices.USE_STATS_FOR_PARALLELIZATION, Boolean.toString(false));
        props.put(QueryServices.TASK_HANDLING_INTERVAL_MS_ATTRIB,
                Long.toString(Long.MAX_VALUE));
        props.put(QueryServices.TASK_HANDLING_INITIAL_DELAY_MS_ATTRIB,
                Long.toString(Long.MAX_VALUE));
        props.put("hbase.coprocessor.master.classes", PhoenixMasterObserver.class.getName());
        setUpTestDriver(new ReadOnlyProps(props.entrySet().iterator()));
        TaskRegionEnvironment =
                getUtility()
                        .getRSForFirstRegionInTable(
                                PhoenixDatabaseMetaData.SYSTEM_TASK_HBASE_TABLE_NAME)
                        .getRegions(PhoenixDatabaseMetaData.SYSTEM_TASK_HBASE_TABLE_NAME)
                        .get(0).getCoprocessorHost()
                        .findCoprocessorEnvironment(TaskRegionObserver.class.getName());
    }

    @Test
    public void testStreamPartitionMetadataBootstrap() throws Exception {
        Connection conn = newConnection();
        String tableName = generateUniqueName();
        String cdcName = generateUniqueName();
        String cdc_sql = "CREATE CDC " + cdcName + " ON " + tableName;
        conn.createStatement().execute(
                "CREATE TABLE  " + tableName + " ( k INTEGER PRIMARY KEY," + " v1 INTEGER,"
                        + " v2 DATE)");
        createCDC(conn, cdc_sql, null);
        String streamName = getStreamName(conn, tableName, cdcName);

        // stream should be in ENABLING state
        assertStreamStatus(conn, tableName, streamName, CDCUtil.CdcStreamStatus.ENABLING);

        // run task to populate partitions and enable stream
        TaskRegionObserver.SelfHealingTask task =
                new TaskRegionObserver.SelfHealingTask(
                        TaskRegionEnvironment, QueryServicesOptions.DEFAULT_TASK_HANDLING_MAX_INTERVAL_MS);
        task.run();

        // stream should be in ENABLED state and metadata is populated for every table region
        assertPartitionMetadata(conn, tableName, cdcName);
        assertStreamStatus(conn, tableName, streamName, CDCUtil.CdcStreamStatus.ENABLED);
    }

    @Test
    public void testOnlyOneStreamAllowed() throws Exception {
        Connection conn = newConnection();
        String tableName = generateUniqueName();
        String cdcName = generateUniqueName();
        String cdc_sql = "CREATE CDC " + cdcName + " ON " + tableName;
        conn.createStatement().execute(
                "CREATE TABLE  " + tableName + " ( k INTEGER PRIMARY KEY," + " v1 INTEGER,"
                        + " v2 DATE)");
        createCDC(conn, cdc_sql, null);
        String streamName = getStreamName(conn, tableName, cdcName);

        // stream exists in ENABLING status
        String cdcName2 = generateUniqueName();
        String cdc_sql2 = "CREATE CDC " + cdcName2 + " ON " + tableName;
        try {
            createCDC(conn, cdc_sql2, null);
            Assert.fail("Only one CDC entity is allowed per table");
        } catch (SQLException e) {
            // expected
            assertEquals(SQLExceptionCode.CDC_ALREADY_ENABLED.getErrorCode(), e.getErrorCode());
            assertTrue(e.getMessage().contains(streamName));
        }

        // run task to populate partitions and enable stream
        TaskRegionObserver.SelfHealingTask task =
                new TaskRegionObserver.SelfHealingTask(
                        TaskRegionEnvironment, QueryServicesOptions.DEFAULT_TASK_HANDLING_MAX_INTERVAL_MS);
        task.run();

        // stream exists in ENABLED status
        try {
            createCDC(conn, cdc_sql2, null);
            Assert.fail("Only one CDC entity is allowed per table");
        } catch (SQLException e) {
            // expected
            assertEquals(SQLExceptionCode.CDC_ALREADY_ENABLED.getErrorCode(), e.getErrorCode());
            assertTrue(e.getMessage().contains(streamName));
        }

        //drop cdc
        dropCDC(conn, cdcName, tableName);
        assertStreamStatus(conn, tableName, streamName, CDCUtil.CdcStreamStatus.DISABLED);

        //create new CDC
        String cdcName3 = generateUniqueName();
        String cdc_sql3 = "CREATE CDC " + cdcName3 + " ON " + tableName;
        createCDC(conn, cdc_sql3, null);
        streamName = getStreamName(conn, tableName, cdcName3);
        assertStreamStatus(conn, tableName, streamName, CDCUtil.CdcStreamStatus.ENABLING);
    }

    /**
     * Split the only region of the table with empty start key and empty end key.
     */
    @Test
    public void testPartitionMetadataTableWithSingleRegionSplits() throws Exception {
        // create table, cdc and bootstrap stream metadata
        Connection conn = newConnection();
        String tableName = generateUniqueName();
        String cdcName = generateUniqueName();
        String cdc_sql = "CREATE CDC " + cdcName + " ON " + tableName;
        conn.createStatement().execute(
                "CREATE TABLE  " + tableName + " ( k VARCHAR PRIMARY KEY," + " v1 INTEGER,"
                        + " v2 VARCHAR)");
        createCDC(conn, cdc_sql, null);
        String streamName = getStreamName(conn, tableName, cdcName);
        TaskRegionObserver.SelfHealingTask task =
                new TaskRegionObserver.SelfHealingTask(
                        TaskRegionEnvironment, QueryServicesOptions.DEFAULT_TASK_HANDLING_MAX_INTERVAL_MS);
        task.run();
        assertStreamStatus(conn, tableName, streamName, CDCUtil.CdcStreamStatus.ENABLED);

        //upsert sample data
        conn.createStatement().execute("UPSERT INTO " + tableName + " VALUES ('a', 1, 'foo')");
        conn.createStatement().execute("UPSERT INTO " + tableName + " VALUES ('b', 2, 'bar')");
        conn.createStatement().execute("UPSERT INTO " + tableName + " VALUES ('k', 3, 'alice')");
        conn.createStatement().execute("UPSERT INTO " + tableName + " VALUES ('p', 4, 'bob')");
        conn.createStatement().execute("UPSERT INTO " + tableName + " VALUES ('w', 5, 'cat')");

        //split the only region somewhere in the middle
        splitTable(conn, tableName, Bytes.toBytes("m"));

        //check partition metadata - daughter regions are inserted and parent's end time is updated.
        ResultSet rs = conn.createStatement().executeQuery(
                "SELECT * FROM SYSTEM.CDC_STREAM WHERE TABLE_NAME='" + tableName + "'");
        PartitionMetadata parent = null;
        List<PartitionMetadata> daughters = new ArrayList<>();
        while (rs.next()) {
            // parent which was split
            if (rs.getLong(6) > 0) {
                parent = new PartitionMetadata(rs);
            } else {
                daughters.add(new PartitionMetadata(rs));
            }
        }
        assertNotNull(parent);
        assertEquals(2, daughters.size());
        assertEquals(daughters.get(0).startTime, parent.endTime);
        assertEquals(daughters.get(1).startTime, parent.endTime);
        assertEquals(parent.partitionId, daughters.get(0).parentPartitionId);
        assertEquals(parent.partitionId, daughters.get(1).parentPartitionId);
        assertTrue(daughters.stream().anyMatch(d -> d.startKey == null && d.endKey != null && d.endKey[0] == 'm'));
        assertTrue(daughters.stream().anyMatch(d -> d.endKey == null && d.startKey != null && d.startKey[0] == 'm'));
    }

    private String getStreamName(Connection conn, String tableName, String cdcName) throws SQLException {
        return String.format(CDC_STREAM_NAME_FORMAT, tableName, cdcName, CDCUtil.getCDCCreationTimestamp(
                conn.unwrap(PhoenixConnection.class).getTableNoCache(tableName)));
    }

    private void assertStreamStatus(Connection conn, String tableName, String streamName,
                                    CDCUtil.CdcStreamStatus status) throws SQLException {
        ResultSet rs = conn.createStatement().executeQuery("SELECT STREAM_STATUS FROM "
                + SYSTEM_CDC_STREAM_STATUS_NAME + " WHERE TABLE_NAME='" + tableName +
                "' AND STREAM_NAME='" + streamName + "'");
        assertTrue(rs.next());
        assertEquals(status.getSerializedValue(), rs.getString(1));
    }

    private void assertPartitionMetadata(Connection conn, String tableName, String cdcName)
            throws SQLException {
        String streamName = String.format(CDC_STREAM_NAME_FORMAT, tableName, cdcName,
                CDCUtil.getCDCCreationTimestamp(conn.unwrap(PhoenixConnection.class).getTableNoCache(tableName)));
        List<HRegionLocation> tableRegions
                = conn.unwrap(PhoenixConnection.class).getQueryServices().getAllTableRegions(tableName.getBytes());
        for (HRegionLocation tableRegion : tableRegions) {
            RegionInfo ri = tableRegion.getRegionInfo();
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + SYSTEM_CDC_STREAM_NAME +
                    " WHERE TABLE_NAME = ? AND STREAM_NAME = ? AND PARTITION_ID= ?");
            ps.setString(1, tableName);
            ps.setString(2, streamName);
            ps.setString(3, ri.getEncodedName());
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
        }
    }

    private void splitTable(Connection conn, String tableName, byte[] splitPoint) throws Exception {
        byte[] tableNameBytes = Bytes.toBytes(tableName);
        ConnectionQueryServices services = conn.unwrap(PhoenixConnection.class).getQueryServices();
        int queryTimeout = services.getProps()
                .getInt(QueryServices.THREAD_TIMEOUT_MS_ATTRIB,
                        QueryServicesOptions.DEFAULT_THREAD_TIMEOUT_MS);
        int nRegions = services.getAllTableRegions(tableNameBytes, queryTimeout).size();
        int nInitialRegions = nRegions;
        Admin admin = services.getAdmin();
        try {
            admin.split(TableName.valueOf(tableName), splitPoint);
            int nTries = 0;
            while (nRegions == nInitialRegions && nTries < 10) {
                Thread.sleep(1000);
                nRegions = services.getAllTableRegions(tableNameBytes, queryTimeout).size();
                nTries++;
            }
        } finally {
            admin.close();
        }
    }

    /**
     * Inner class to represent partition metadata for a region i.e. single row from SYSTEM.CDC_STREAM
     */
    private class PartitionMetadata {
        public String partitionId;
        public String parentPartitionId;
        public Long startTime;
        public Long endTime;
        public byte[] startKey;
        public byte[] endKey;

        public PartitionMetadata(ResultSet rs) throws SQLException {
            partitionId = rs.getString(3);
            parentPartitionId = rs.getString(4);
            startTime = rs.getLong(5);
            endTime = rs.getLong(6);
            startKey = rs.getBytes(7);
            endKey = rs.getBytes(8);
        }
    }
}
