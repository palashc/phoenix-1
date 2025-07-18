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
package org.apache.phoenix.schema;

import org.apache.hadoop.hbase.HConstants;
import org.apache.phoenix.compat.hbase.ByteStringer;
import org.apache.phoenix.coprocessor.generated.PTableProtos;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.SizedUtil;

import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

public class PColumnImpl implements PColumn {
  private PName name;
  private PName familyName;
  private PDataType dataType;
  private Integer maxLength;
  private Integer scale;
  private boolean nullable;
  private int position;
  private SortOrder sortOrder;
  private Integer arraySize;
  private byte[] viewConstant;
  private boolean isViewReferenced;
  private String expressionStr;
  private boolean isRowTimestamp;
  private boolean isDynamic;
  private byte[] columnQualifierBytes;
  private boolean derived;
  private long timestamp;

  public PColumnImpl() {
  }

  public PColumnImpl(PColumn column, int position) {
    this(column, column.isDerived(), position);
  }

  public PColumnImpl(PColumn column, byte[] viewConstant, boolean isViewReferenced) {
    this(column.getName(), column.getFamilyName(), column.getDataType(), column.getMaxLength(),
      column.getScale(), column.isNullable(), column.getPosition(), column.getSortOrder(),
      column.getArraySize(), viewConstant, isViewReferenced, column.getExpressionStr(),
      column.isRowTimestamp(), column.isDynamic(), column.getColumnQualifierBytes(),
      column.getTimestamp(), column.isDerived());
  }

  public PColumnImpl(PColumn column, boolean derivedColumn, int position) {
    this(column, derivedColumn, position, column.getViewConstant());
  }

  public PColumnImpl(PColumn column, boolean derivedColumn, int position, byte[] viewConstant) {
    this(column.getName(), column.getFamilyName(), column.getDataType(), column.getMaxLength(),
      column.getScale(), column.isNullable(), position, column.getSortOrder(),
      column.getArraySize(), viewConstant, column.isViewReferenced(), column.getExpressionStr(),
      column.isRowTimestamp(), column.isDynamic(), column.getColumnQualifierBytes(),
      column.getTimestamp(), derivedColumn);
  }

  public PColumnImpl(PName name, PName familyName, PDataType dataType, Integer maxLength,
    Integer scale, boolean nullable, int position, SortOrder sortOrder, Integer arrSize,
    byte[] viewConstant, boolean isViewReferenced, String expressionStr, boolean isRowTimestamp,
    boolean isDynamic, byte[] columnQualifierBytes, long timestamp) {
    this(name, familyName, dataType, maxLength, scale, nullable, position, sortOrder, arrSize,
      viewConstant, isViewReferenced, expressionStr, isRowTimestamp, isDynamic,
      columnQualifierBytes, timestamp, false);
  }

  public PColumnImpl(PName name, PName familyName, PDataType dataType, Integer maxLength,
    Integer scale, boolean nullable, int position, SortOrder sortOrder, Integer arrSize,
    byte[] viewConstant, boolean isViewReferenced, String expressionStr, boolean isRowTimestamp,
    boolean isDynamic, byte[] columnQualifierBytes, long timestamp, boolean derived) {
    init(name, familyName, dataType, maxLength, scale, nullable, position, sortOrder, arrSize,
      viewConstant, isViewReferenced, expressionStr, isRowTimestamp, isDynamic,
      columnQualifierBytes, timestamp, derived);
  }

  private PColumnImpl(PName familyName, PName columnName, Long timestamp) {
    this.familyName = familyName;
    this.name = columnName;
    this.derived = true;
    if (timestamp != null) {
      this.timestamp = timestamp;
    }
  }

  // a excluded column (a column that was derived from a parent but that has been deleted) is
  // denoted by a column that has a null type
  public static PColumnImpl createExcludedColumn(PName familyName, PName columnName,
    Long timestamp) {
    return new PColumnImpl(familyName, columnName, timestamp);
  }

  private void init(PName name, PName familyName, PDataType dataType, Integer maxLength,
    Integer scale, boolean nullable, int position, SortOrder sortOrder, Integer arrSize,
    byte[] viewConstant, boolean isViewReferenced, String expressionStr, boolean isRowTimestamp,
    boolean isDynamic, byte[] columnQualifierBytes, long timestamp, boolean derived) {
    Preconditions.checkNotNull(sortOrder);
    this.dataType = dataType;
    if (familyName == null) {
      // Allow nullable columns in PK, but only if they're variable length.
      // Variable length types may be null, since we use a null-byte terminator
      // (which is a disallowed character in variable length types). However,
      // fixed width types do not have a way of representing null.
      // TODO: we may be able to allow this for columns at the end of the PK
      Preconditions.checkArgument(!nullable || !dataType.isFixedWidth(),
        "PK columns may not be both fixed width and nullable: " + name.getString());
    }
    this.name = name;
    this.familyName = familyName == null ? null : familyName;
    this.maxLength = maxLength;
    this.scale = scale;
    this.nullable = nullable;
    this.position = position;
    this.sortOrder = sortOrder;
    this.arraySize = arrSize;
    this.viewConstant = viewConstant;
    this.isViewReferenced = isViewReferenced;
    this.expressionStr = expressionStr;
    this.isRowTimestamp = isRowTimestamp;
    this.isDynamic = isDynamic;
    this.columnQualifierBytes = columnQualifierBytes;
    this.timestamp = timestamp;
    this.derived = derived;
  }

  @Override
  public int getEstimatedSize() {
    return SizedUtil.OBJECT_SIZE + SizedUtil.POINTER_SIZE * 8 + SizedUtil.INT_OBJECT_SIZE * 3
      + SizedUtil.INT_SIZE + name.getEstimatedSize()
      + (familyName == null ? 0 : familyName.getEstimatedSize())
      + (viewConstant == null ? 0 : (SizedUtil.ARRAY_SIZE + viewConstant.length));
  }

  @Override
  public PName getName() {
    return name;
  }

  @Override
  public PName getFamilyName() {
    return familyName;
  }

  @Override
  public PDataType getDataType() {
    return dataType;
  }

  @Override
  public Integer getMaxLength() {
    return maxLength;
  }

  @Override
  public Integer getScale() {
    return scale;
  }

  @Override
  public String getExpressionStr() {
    return expressionStr;
  }

  @Override
  public long getTimestamp() {
    return timestamp;
  }

  @Override
  public boolean isExcluded() {
    return dataType == null;
  }

  @Override
  public boolean isNullable() {
    return nullable;
  }

  @Override
  public int getPosition() {
    return position;
  }

  @Override
  public SortOrder getSortOrder() {
    return sortOrder;
  }

  @Override
  public String toString() {
    return (familyName == null ? "" : familyName.toString() + QueryConstants.NAME_SEPARATOR)
      + name.toString();
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((familyName == null) ? 0 : familyName.hashCode());
    result = prime * result + ((name == null) ? 0 : name.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (!(obj instanceof PColumn)) return false;
    PColumn other = (PColumn) obj;
    if (familyName == null) {
      if (other.getFamilyName() != null) return false;
    } else if (!familyName.equals(other.getFamilyName())) return false;
    if (name == null) {
      if (other.getName() != null) return false;
    } else if (!name.equals(other.getName())) return false;
    return true;
  }

  @Override
  public Integer getArraySize() {
    return arraySize;
  }

  @Override
  public byte[] getViewConstant() {
    return viewConstant;
  }

  @Override
  public boolean isViewReferenced() {
    return isViewReferenced;
  }

  @Override
  public boolean isRowTimestamp() {
    return isRowTimestamp;
  }

  @Override
  public boolean isDynamic() {
    return isDynamic;
  }

  @Override
  public byte[] getColumnQualifierBytes() {
    // Needed for backward compatibility
    if (!SchemaUtil.isPKColumn(this) && columnQualifierBytes == null) {
      return this.name.getBytes();
    }
    return columnQualifierBytes;
  }

  /**
   * Create a PColumn instance from PBed PColumn instance
   */
  public static PColumn createFromProto(PTableProtos.PColumn column) {
    byte[] columnNameBytes = column.getColumnNameBytes().toByteArray();
    PName columnName = PNameFactory.newName(columnNameBytes);
    PName familyName = null;
    if (column.hasFamilyNameBytes()) {
      familyName = PNameFactory.newName(column.getFamilyNameBytes().toByteArray());
    }
    PDataType dataType =
      column.hasDataType() ? PDataType.fromSqlTypeName(column.getDataType()) : null;
    Integer maxLength = null;
    if (column.hasMaxLength()) {
      maxLength = column.getMaxLength();
    }
    Integer scale = null;
    if (column.hasScale()) {
      scale = column.getScale();
    }
    boolean nullable = column.getNullable();
    int position = column.getPosition();
    SortOrder sortOrder = SortOrder.fromSystemValue(column.getSortOrder());
    Integer arraySize = null;
    if (column.hasArraySize()) {
      arraySize = column.getArraySize();
    }
    byte[] viewConstant = null;
    if (column.hasViewConstant()) {
      viewConstant = column.getViewConstant().toByteArray();
    }
    boolean isViewReferenced = false;
    if (column.hasViewReferenced()) {
      isViewReferenced = column.getViewReferenced();
    }
    String expressionStr = null;
    if (column.hasExpression()) {
      expressionStr = column.getExpression();
    }
    boolean isRowTimestamp = column.getIsRowTimestamp();
    boolean isDynamic = false;
    if (column.hasIsDynamic()) {
      isDynamic = column.getIsDynamic();
    }
    byte[] columnQualifierBytes = null;
    if (column.hasColumnQualifierBytes()) {
      columnQualifierBytes = column.getColumnQualifierBytes().toByteArray();
    }
    long timestamp = HConstants.LATEST_TIMESTAMP;
    if (column.hasTimestamp()) {
      timestamp = column.getTimestamp();
    }
    boolean derived = false;
    if (column.hasDerived()) {
      derived = column.getDerived();
    }
    return new PColumnImpl(columnName, familyName, dataType, maxLength, scale, nullable, position,
      sortOrder, arraySize, viewConstant, isViewReferenced, expressionStr, isRowTimestamp,
      isDynamic, columnQualifierBytes, timestamp, derived);
  }

  public static PTableProtos.PColumn toProto(PColumn column) {
    PTableProtos.PColumn.Builder builder = PTableProtos.PColumn.newBuilder();
    builder.setColumnNameBytes(ByteStringer.wrap(column.getName().getBytes()));
    if (column.getFamilyName() != null) {
      builder.setFamilyNameBytes(ByteStringer.wrap(column.getFamilyName().getBytes()));
    }
    if (column.getDataType() != null) {
      builder.setDataType(column.getDataType().getSqlTypeName());
    }
    if (column.getMaxLength() != null) {
      builder.setMaxLength(column.getMaxLength());
    }
    if (column.getScale() != null) {
      builder.setScale(column.getScale());
    }
    builder.setNullable(column.isNullable());
    builder.setPosition(column.getPosition());
    if (column.getSortOrder() != null) {
      builder.setSortOrder(column.getSortOrder().getSystemValue());
    }
    if (column.getArraySize() != null) {
      builder.setArraySize(column.getArraySize());
    }
    if (column.getViewConstant() != null) {
      builder.setViewConstant(ByteStringer.wrap(column.getViewConstant()));
    }
    builder.setViewReferenced(column.isViewReferenced());

    if (column.getExpressionStr() != null) {
      builder.setExpression(column.getExpressionStr());
    }
    builder.setIsRowTimestamp(column.isRowTimestamp());
    if (column.getColumnQualifierBytes() != null) {
      builder.setColumnQualifierBytes(ByteStringer.wrap(column.getColumnQualifierBytes()));
    }
    if (column.getTimestamp() != HConstants.LATEST_TIMESTAMP) {
      builder.setTimestamp(column.getTimestamp());
    }
    builder.setDerived(column.isDerived());
    return builder.build();
  }

  public boolean isDerived() {
    return derived;
  }
}
