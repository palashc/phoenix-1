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
package org.apache.phoenix.util;

import java.sql.SQLException;
import java.sql.Types;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TimeZone;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.util.StringUtils;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.execute.DescVarLengthFastByteComparisons;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.types.PDataType;
import org.bson.RawBsonDocument;

public class CDCUtil {
  public static final String CDC_INDEX_PREFIX = "PHOENIX_CDC_INDEX_";

  // phoenix/cdc/stream/{tableName}/{cdc object name}/{cdc index timestamp}/{cdc index creation
  // datetime}
  public static String CDC_STREAM_NAME_FORMAT = "phoenix/cdc/stream/%s/%s/%d/%s";

  /**
   * Make a set of CDC change scope enums from the given string containing comma separated scope
   * names.
   * @param includeScopes Comma-separated scope names.
   * @return the set of enums, which can be empty if the string is empty or has no valid names.
   */
  public static Set<PTable.CDCChangeScope> makeChangeScopeEnumsFromString(String includeScopes)
    throws SQLException {
    Set<PTable.CDCChangeScope> cdcChangeScopes = new HashSet<>();
    if (includeScopes != null) {
      StringTokenizer st = new StringTokenizer(includeScopes, ",");
      while (st.hasMoreTokens()) {
        String tok = st.nextToken();
        try {
          cdcChangeScopes.add(PTable.CDCChangeScope.valueOf(tok.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
          throw new SQLExceptionInfo.Builder(SQLExceptionCode.UNKNOWN_INCLUDE_CHANGE_SCOPE)
            .setCdcChangeScope(tok).build().buildException();
        }
      }
    }
    return cdcChangeScopes;
  }

  /**
   * Make a string of comma-separated scope names from the specified set of enums.
   * @param includeScopes Set of scope enums
   * @return the comma-separated string of scopes, which can be an empty string in case the set is
   *         empty.
   */
  public static String makeChangeScopeStringFromEnums(Set<PTable.CDCChangeScope> includeScopes) {
    String cdcChangeScopes = null;
    if (includeScopes != null) {
      Iterable<String> tmpStream =
        () -> includeScopes.stream().sorted().map(s -> s.name()).iterator();
      cdcChangeScopes = StringUtils.join(",", tmpStream);
    }
    return cdcChangeScopes;
  }

  public static String getCDCIndexName(String cdcName) {
    return CDC_INDEX_PREFIX + SchemaUtil.getTableNameFromFullName(cdcName);
  }

  public static boolean isCDCIndex(String indexName) {
    return indexName.startsWith(CDC_INDEX_PREFIX);
  }

  public static boolean isCDCIndex(byte[] indexNameBytes) {
    String indexName = Bytes.toString(indexNameBytes);
    return isCDCIndex(indexName);
  }

  public static byte[] getCdcObjectName(byte[] cdcIndexName) {
    return Arrays.copyOfRange(cdcIndexName, CDC_INDEX_PREFIX.length(), cdcIndexName.length);
  }

  public static boolean isCDCIndex(PTable indexTable) {
    return isCDCIndex(indexTable.getTableName().getString());
  }

  public static boolean isCDCIndexActive(PTable indexTable) {
    return isCDCIndex(indexTable.getTableName().getString())
      && indexTable.getIndexState() == PIndexState.ACTIVE;
  }

  /**
   * Check if the given table has an active CDC index.
   * @param table The PTable object.
   * @return true if the table has an active CDC index, false otherwise.
   */
  public static boolean hasActiveCDCIndex(PTable table) {
    if (table == null || table.getIndexes() == null) {
      return false;
    }
    return table.getIndexes().stream().anyMatch(CDCUtil::isCDCIndexActive);
  }

  /**
   * Return PTable of the active CDC index for the given data table.
   * @param dataTable The data table.
   * @return active CDC index.
   */
  public static PTable getActiveCDCIndex(PTable dataTable) {
    return dataTable.getIndexes().stream().filter(CDCUtil::isCDCIndexActive).findFirst()
      .orElse(null);
  }

  /**
   * Check if the given table has any CDC indexes.
   * @param table The PTable object.
   * @return true if the table has an CDC index, false otherwise.
   */
  public static boolean hasCDCIndex(PTable table) {
    if (table == null || table.getIndexes() == null) {
      return false;
    }
    return table.getIndexes().stream().anyMatch(CDCUtil::isCDCIndex);
  }

  public static Scan setupScanForCDC(Scan scan) {
    scan.setRaw(true);
    scan.readAllVersions();
    scan.setCacheBlocks(false);
    Map<byte[], NavigableSet<byte[]>> familyMap = scan.getFamilyMap();
    if (!familyMap.isEmpty()) {
      familyMap.clear();
    }
    return scan;
  }

  public static int compareCellFamilyAndQualifier(byte[] columnFamily1, byte[] columnQual1,
    byte[] columnFamily2, byte[] columnQual2) {
    int familyNameComparison = DescVarLengthFastByteComparisons.compareTo(columnFamily1, 0,
      columnFamily1.length, columnFamily2, 0, columnFamily2.length);
    if (familyNameComparison != 0) {
      return familyNameComparison;
    }
    return DescVarLengthFastByteComparisons.compareTo(columnQual1, 0, columnQual1.length,
      columnQual2, 0, columnQual2.length);
  }

  public static Object getColumnEncodedValue(Object value, PDataType dataType) {
    if (value != null) {
      if (dataType.getSqlType() == PDataType.BSON_TYPE) {
        value = ByteUtil.toBytes(((RawBsonDocument) value).getByteBuffer().asNIO());
      } else if (isBinaryType(dataType)) {
        // Unfortunately, Base64.Encoder has no option to specify offset and length so can't
        // avoid copying bytes.
        value = Base64.getEncoder().encodeToString((byte[]) value);
      } else {
        int sqlType = dataType.getSqlType();
        if (
          sqlType == Types.DATE || sqlType == Types.TIMESTAMP || sqlType == Types.TIME
            || sqlType == Types.TIME_WITH_TIMEZONE || dataType.isArrayType()
            || sqlType == PDataType.JSON_TYPE || sqlType == Types.TIMESTAMP_WITH_TIMEZONE
        ) {
          value = value.toString();
        }
      }
    }
    return value;
  }

  public static boolean isBinaryType(PDataType dataType) {
    int sqlType = dataType.getSqlType();
    return (sqlType == Types.BINARY || sqlType == Types.VARBINARY || sqlType == Types.LONGVARBINARY
      || dataType.getSqlType() == PDataType.VARBINARY_ENCODED_TYPE);
  }

  public enum CdcStreamStatus {
    ENABLED("ENABLED"),
    ENABLING("ENABLING"),
    DISABLED("DISABLED"),
    DISABLING("DISABLING");

    private final String serializedValue;

    private CdcStreamStatus(String value) {
      this.serializedValue = value;
    }

    public String getSerializedValue() {
      return serializedValue;
    }
  }

  public static long getCDCCreationTimestamp(PTable table) {
    for (PTable index : table.getIndexes()) {
      if (CDCUtil.isCDCIndex(index)) {
        return index.getTimeStamp();
      }
    }
    return -1;
  }

  public static String getCDCCreationUTCDateTime(long timestamp) {
    Date date = new Date(timestamp);
    DateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
    format.setTimeZone(TimeZone.getTimeZone("Etc/UTC"));
    return format.format(date);
  }
}
