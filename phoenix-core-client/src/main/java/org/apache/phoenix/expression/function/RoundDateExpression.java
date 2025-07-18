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
package org.apache.phoenix.expression.function;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.Date;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.hadoop.hbase.CompareOperator;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.phoenix.compile.KeyPart;
import org.apache.phoenix.expression.Determinism;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.LiteralExpression;
import org.apache.phoenix.parse.FunctionParseNode.Argument;
import org.apache.phoenix.parse.FunctionParseNode.BuiltInFunction;
import org.apache.phoenix.parse.FunctionParseNode.FunctionClassType;
import org.apache.phoenix.query.KeyRange;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PDataType.PDataCodec;
import org.apache.phoenix.schema.types.PDate;
import org.apache.phoenix.schema.types.PInteger;
import org.apache.phoenix.schema.types.PVarchar;
import org.apache.phoenix.util.ByteUtil;

import org.apache.phoenix.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.phoenix.thirdparty.com.google.common.collect.Lists;

/**
 * Function used to bucketize date/time values by rounding them to an even increment. Usage:
 * {@code ROUND(<date/time col ref>,<'day'|'hour'|'minute'|'second'|'millisecond'|'week'|'month'|'year'>,<optional integer multiplier>) }
 * The integer multiplier is optional and is used to do rollups to a partial time unit (i.e. 10
 * minute rollup) The function returns a {@link org.apache.phoenix.schema.types.PDate}
 * @since 0.1
 */
@BuiltInFunction(name = RoundFunction.NAME,
    args = { @Argument(allowedTypes = { PDate.class }),
      @Argument(allowedTypes = { PVarchar.class, PInteger.class }, defaultValue = "null",
          isConstant = true),
      @Argument(allowedTypes = { PInteger.class }, defaultValue = "1", isConstant = true) },
    classType = FunctionClassType.DERIVED)
public class RoundDateExpression extends ScalarFunction {

  long divBy;

  public static final String NAME = "ROUND";

  private static final long[] TIME_UNIT_MS =
    new long[] { 24 * 60 * 60 * 1000, 60 * 60 * 1000, 60 * 1000, 1000, 1 };

  public RoundDateExpression() {
  }

  /**
   * @param timeUnit - unit of time to round up to. Creates a {@link RoundDateExpression} with
   *                 default multiplier of 1.
   */
  public static Expression create(Expression expr, TimeUnit timeUnit) throws SQLException {
    return create(expr, timeUnit, 1);
  }

  /**
   * @param timeUnit   - unit of time to round up to
   * @param multiplier - determines the roll up window size. Create a {@link RoundDateExpression}.
   */
  public static Expression create(Expression expr, TimeUnit timeUnit, int multiplier)
    throws SQLException {
    Expression timeUnitExpr = getTimeUnitExpr(timeUnit);
    Expression defaultMultiplierExpr = getMultiplierExpr(multiplier);
    List<Expression> expressions = Lists.newArrayList(expr, timeUnitExpr, defaultMultiplierExpr);
    return create(expressions);
  }

  public static Expression create(List<Expression> children) throws SQLException {
    int numChildren = children.size();
    if (numChildren < 2 || numChildren > 3) {
      throw new IllegalArgumentException("Wrong number of arguments : " + numChildren);
    }
    Object timeUnitValue = ((LiteralExpression) children.get(1)).getValue();
    TimeUnit timeUnit =
      TimeUnit.getTimeUnit(timeUnitValue != null ? timeUnitValue.toString() : null);
    switch (timeUnit) {
      case WEEK:
        return new RoundWeekExpression(children);
      case MONTH:
        return new RoundMonthExpression(children);
      case YEAR:
        return new RoundYearExpression(children);
      default:
        return new RoundDateExpression(children);
    }

  }

  static Expression getTimeUnitExpr(TimeUnit timeUnit) throws SQLException {
    return LiteralExpression.newConstant(timeUnit.name(), PVarchar.INSTANCE, Determinism.ALWAYS);
  }

  static Expression getMultiplierExpr(int multiplier) throws SQLException {
    return LiteralExpression.newConstant(multiplier, PInteger.INSTANCE, Determinism.ALWAYS);
  }

  public RoundDateExpression(List<Expression> children) {
    super(children.subList(0, 1));
    int numChildren = children.size();
    Object timeUnitValue = ((LiteralExpression) children.get(1)).getValue();
    Object multiplierValue =
      numChildren > 2 ? ((LiteralExpression) children.get(2)).getValue() : null;
    int multiplier = multiplierValue == null ? 1 : ((Number) multiplierValue).intValue();
    TimeUnit timeUnit =
      TimeUnit.getTimeUnit(timeUnitValue != null ? timeUnitValue.toString() : null);
    if (timeUnit.ordinal() < TIME_UNIT_MS.length) {
      divBy = multiplier * TIME_UNIT_MS[timeUnit.ordinal()];
    }
  }

  protected long getRoundUpAmount() {
    return divBy / 2;
  }

  @VisibleForTesting
  public long roundTime(long time) {
    long value;
    long roundUpAmount = getRoundUpAmount();
    if (time <= Long.MAX_VALUE - roundUpAmount) { // If no overflow, add
      value = (time + roundUpAmount) / divBy;
    } else { // Else subtract and add one
      value = (time - roundUpAmount) / divBy + 1;
    }
    return value * divBy;
  }

  @VisibleForTesting
  public long rangeLower(long time) {
    // This is for the ms based intervals. This needs to be separately implemented for the
    // joda based intervals
    return roundTime(time) - getRoundUpAmount();
  }

  @VisibleForTesting
  public long rangeUpper(long time) {
    // This is for the ms based intervals. This needs to be separately implemented for the
    // joda based intervals
    return roundTime(time) + (divBy - getRoundUpAmount()) - 1;
  }

  @Override
  public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {
    if (children.get(0).evaluate(tuple, ptr)) {
      if (ptr.getLength() == 0) {
        return true; // child evaluated to null
      }
      PDataType dataType = getDataType();
      long time = dataType.getCodec().decodeLong(ptr, children.get(0).getSortOrder());
      long value = roundTime(time);
      Date d = new Date(value);
      byte[] byteValue = dataType.toBytes(d);
      ptr.set(byteValue);
      return true;
    }
    return false;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    long roundUpAmount = this.getRoundUpAmount();
    result = prime * result + (int) (divBy ^ (divBy >>> 32));
    result = prime * result + (int) (roundUpAmount ^ (roundUpAmount >>> 32));
    result = prime * result + children.get(0).hashCode();
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    RoundDateExpression other = (RoundDateExpression) obj;
    if (divBy != other.divBy) return false;
    if (getRoundUpAmount() != other.getRoundUpAmount()) return false;
    return children.get(0).equals(other.children.get(0));
  }

  @Override
  public void readFields(DataInput input) throws IOException {
    super.readFields(input);
    divBy = WritableUtils.readVLong(input);
  }

  @Override
  public void write(DataOutput output) throws IOException {
    super.write(output);
    WritableUtils.writeVLong(output, divBy);
  }

  @Override
  public Integer getMaxLength() {
    return children.get(0).getMaxLength();
  }

  @Override
  public PDataType getDataType() {
    return children.get(0).getDataType();
  }

  @Override
  public boolean isNullable() {
    return children.get(0).isNullable() || divBy == 0;
  }

  protected PDataCodec getKeyRangeCodec(PDataType columnDataType) {
    return columnDataType.getCodec();
  }

  /**
   * Form the key range from the key to the key right before or at the next rounded value.
   */
  @Override
  public KeyPart newKeyPart(final KeyPart childPart) {
    return new KeyPart() {
      private final Set<Expression> extractNodes =
        new LinkedHashSet<>(Collections.<Expression> singleton(RoundDateExpression.this));

      @Override
      public PColumn getColumn() {
        return childPart.getColumn();
      }

      @Override
      public Set<Expression> getExtractNodes() {
        return extractNodes;
      }

      @Override
      public KeyRange getKeyRange(CompareOperator op, Expression rhs) {
        PDataType type = getColumn().getDataType();
        ImmutableBytesWritable ptr = new ImmutableBytesWritable();
        rhs.evaluate(null, ptr);
        byte[] key = ByteUtil.copyKeyBytesIfNecessary(ptr);
        // No need to take into account SortOrder, because ROUND
        // always forces the value to be in ascending order
        PDataCodec codec = getKeyRangeCodec(type);
        int offset = ByteUtil.isInclusive(op) ? 1 : 0;
        long value = codec.decodeLong(key, 0, SortOrder.getDefault());
        byte[] lowerKey = new byte[type.getByteSize()];
        byte[] upperKey = new byte[type.getByteSize()];
        SortOrder order = this.getColumn().getSortOrder();
        KeyRange range;
        switch (op) {
          case EQUAL:
            // If the value isn't evenly divisible by the div amount, then it
            // can't possibly be equal to any rounded value. For example, if you
            // had ROUND(dateCol,'DAY') = TO_DATE('2013-01-01 23:00:00')
            // it could never be equal, since date constant isn't at a day
            // boundary.
            if (value != roundTime(value)) {
              return KeyRange.EMPTY_RANGE;
            }
            codec.encodeLong(rangeLower(value), lowerKey, 0);
            codec.encodeLong(rangeUpper(value), upperKey, 0);
            range = type.getKeyRange(lowerKey, true, upperKey, true, order);
            break;
          // a simple number example (with half up rounding):
          // round(x) = 10 ==> [9.5, 10.5)
          // round(x) <= 10 ==> [-inf, 10.5)
          // round(x) <= 10.1 === round(x) <= 10 => [-inf, 10.5)
          // round(x) <= 9.9 === round(x) <= 9 => [-inf, 9.5)
          // round(x) < 10 ==> round(x) <= 9 ==> [-inf, 9.5)
          case GREATER:
            if (value == roundTime(value)) {
              codec.encodeLong(rangeUpper(value), lowerKey, 0);
              range = type.getKeyRange(lowerKey, false, KeyRange.UNBOUND, false, order);
              break;
            }
            // fallthrough intended
          case GREATER_OR_EQUAL:
            codec.encodeLong(rangeLower(value), lowerKey, 0);
            range = type.getKeyRange(lowerKey, true, KeyRange.UNBOUND, false, order);
            if (value <= roundTime(value)) {
              // always true for ceil
              codec.encodeLong(rangeLower(value), lowerKey, 0);
              range = type.getKeyRange(lowerKey, true, KeyRange.UNBOUND, false, order);
            } else {
              // always true for floor, except when exact
              codec.encodeLong(rangeUpper(value), lowerKey, 0);
              range = type.getKeyRange(lowerKey, false, KeyRange.UNBOUND, false, order);
            }
            break;
          case LESS:
            if (value == roundTime(value)) {
              codec.encodeLong(rangeLower(value), upperKey, 0);
              range = type.getKeyRange(KeyRange.UNBOUND, false, upperKey, false, order);
              break;
            }
            // fallthrough intended
          case LESS_OR_EQUAL:
            codec.encodeLong(rangeUpper(value), upperKey, 0);
            range = type.getKeyRange(KeyRange.UNBOUND, false, upperKey, true, order);
            if (value >= roundTime(value)) {
              // always true for floor
              codec.encodeLong(rangeUpper(value), upperKey, 0);
              range = type.getKeyRange(KeyRange.UNBOUND, false, upperKey, true, order);
            } else {
              // always true for ceil, except when exact
              codec.encodeLong(rangeLower(value), upperKey, 0);
              range = type.getKeyRange(KeyRange.UNBOUND, false, upperKey, false, order);
            }
            break;
          default:
            return childPart.getKeyRange(op, rhs);
        }
        if (getColumn().getSortOrder() == SortOrder.DESC) {
          range = range.invert();
        }
        return range;
      }

      @Override
      public PTable getTable() {
        return childPart.getTable();
      }
    };
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public OrderPreserving preservesOrder() {
    return OrderPreserving.YES;
  }

  @Override
  public int getKeyFormationTraversalIndex() {
    return 0;
  }
}
