/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.phoenix.schema.metrics;

import org.apache.hadoop.hbase.metrics.BaseSourceImpl;
import org.apache.hadoop.metrics2.lib.MutableFastCounter;

public class MetricsMetadataSourceImpl extends BaseSourceImpl implements MetricsMetadataSource {

    private final MutableFastCounter createTableCount;
    private final MutableFastCounter createViewCount;
    private final MutableFastCounter createIndexCount;
    private final MutableFastCounter createSchemaCount;
    private final MutableFastCounter createFunctionCount;

    private final MutableFastCounter alterAddColumnCount;
    private final MutableFastCounter alterDropColumnCount;

    private final MutableFastCounter dropTableCount;
    private final MutableFastCounter dropViewCount;
    private final MutableFastCounter dropIndexCount;
    private final MutableFastCounter dropSchemaCount;
    private final MutableFastCounter dropFunctionCount;

    public MetricsMetadataSourceImpl() {
        this(METRICS_NAME, METRICS_DESCRIPTION, METRICS_CONTEXT, METRICS_JMX_CONTEXT);
    }

    public MetricsMetadataSourceImpl(String metricsName, String metricsDescription,
                                     String metricsContext, String metricsJmxContext) {
        super(metricsName, metricsDescription, metricsContext, metricsJmxContext);

        createTableCount = getMetricsRegistry().newCounter(CREATE_TABLE_COUNT,
                CREATE_TABLE_COUNT_DESC, 0L);
        createViewCount = getMetricsRegistry().newCounter(CREATE_VIEW_COUNT,
                CREATE_VIEW_COUNT_DESC, 0L);
        createIndexCount = getMetricsRegistry().newCounter(CREATE_INDEX_COUNT,
                CREATE_INDEX_COUNT_DESC, 0L);
        createFunctionCount = getMetricsRegistry().newCounter(CREATE_FUNCTION_COUNT,
                CREATE_FUNCTION_COUNT_DESC, 0L);
        createSchemaCount = getMetricsRegistry().newCounter(CREATE_SCHEMA_COUNT,
                CREATE_SCHEMA_COUNT_DESC, 0L);

        alterAddColumnCount = getMetricsRegistry().newCounter(ALTER_ADD_COLUMN_COUNT,
                ALTER_ADD_COLUMN_COUNT_DESC, 0L);
        alterDropColumnCount = getMetricsRegistry().newCounter(ALTER_DROP_COLUMN_COUNT,
                ALTER_DROP_COLUMN_COUNT_DESC, 0L);

        dropTableCount = getMetricsRegistry().newCounter(DROP_TABLE_COUNT,
                DROP_TABLE_COUNT_DESC, 0L);
        dropViewCount = getMetricsRegistry().newCounter(DROP_VIEW_COUNT,
                DROP_VIEW_COUNT_DESC, 0L);
        dropIndexCount = getMetricsRegistry().newCounter(DROP_INDEX_COUNT,
                DROP_INDEX_COUNT_DESC, 0L);
        dropSchemaCount = getMetricsRegistry().newCounter(DROP_SCHEMA_COUNT,
                DROP_SCHEMA_COUNT_DESC, 0L);
        dropFunctionCount = getMetricsRegistry().newCounter(DROP_FUNCTION_COUNT,
                DROP_FUNCTION_COUNT_DESC, 0L);
    }

    @Override public void incrementCreateTableCount() {
        createTableCount.incr();
    }

    @Override public void incrementCreateViewCount() {
        createViewCount.incr();
    }

    @Override public void incrementCreateIndexCount() {
        createIndexCount.incr();
    }

    @Override public void incrementCreateSchemaCount() {
        createSchemaCount.incr();
    }

    @Override public void incrementCreateFunctionCount() {
        createFunctionCount.incr();
    }

    @Override public void incrementAlterAddColumnCount() {
        alterAddColumnCount.incr();
    }

    @Override public void incrementAlterDropColumnCount() {
        alterDropColumnCount.incr();
    }

    @Override public void incrementDropTableCount() {
        dropTableCount.incr();
    }

    @Override public void incrementDropViewCount() {
        dropViewCount.incr();
    }

    @Override public void incrementDropIndexCount() {
        dropIndexCount.incr();
    }

    @Override public void incrementDropSchemaCount() {
        dropSchemaCount.incr();
    }

    @Override public void incrementDropFunctionCount() {
        dropFunctionCount.incr();
    }
}