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

package org.apache.flink.table.formats.raw;

import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.formats.raw.RawFormatDeserializationSchema;
import org.apache.flink.formats.raw.RawFormatFactory;
import org.apache.flink.formats.raw.RawFormatSerializationSchema;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.TestDynamicTableFactory;
import org.apache.flink.table.runtime.connector.sink.SinkRuntimeProviderContext;
import org.apache.flink.table.runtime.connector.source.ScanRuntimeProviderContext;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.types.logical.RowType;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.apache.flink.core.testutils.FlinkAssertions.anyCauseMatches;
import static org.apache.flink.table.factories.utils.FactoryMocks.createTableSink;
import static org.apache.flink.table.factories.utils.FactoryMocks.createTableSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link RawFormatFactory}. */
class RawFormatFactoryTest {

    private static final ResolvedSchema SCHEMA =
            ResolvedSchema.of(Column.physical("field1", DataTypes.STRING()));

    private static final RowType ROW_TYPE =
            (RowType) SCHEMA.toPhysicalRowDataType().getLogicalType();

    @Test
    void testSeDeSchema() {
        final Map<String, String> tableOptions = getBasicOptions();

        // test deserialization
        final RawFormatDeserializationSchema expectedDeser =
                new RawFormatDeserializationSchema(
                        ROW_TYPE.getTypeAt(0), InternalTypeInfo.of(ROW_TYPE), "UTF-8", true);
        DeserializationSchema<RowData> actualDeser =
                createDeserializationSchema(SCHEMA, tableOptions);
        assertThat(actualDeser).isEqualTo(expectedDeser);

        // test serialization
        final RawFormatSerializationSchema expectedSer =
                new RawFormatSerializationSchema(ROW_TYPE.getTypeAt(0), "UTF-8", true);
        SerializationSchema<RowData> actualSer = createSerializationSchema(SCHEMA, tableOptions);
        assertThat(actualSer).isEqualTo(expectedSer);
    }

    @Test
    void testCharsetAndEndiannessOption() {
        final Map<String, String> tableOptions =
                getModifiedOptions(
                        options -> {
                            options.put("raw.charset", "UTF-16");
                            options.put("raw.endianness", "little-endian");
                        });

        // test deserialization
        final RawFormatDeserializationSchema expectedDeser =
                new RawFormatDeserializationSchema(
                        ROW_TYPE.getTypeAt(0), InternalTypeInfo.of(ROW_TYPE), "UTF-16", false);
        DeserializationSchema<RowData> actualDeser =
                createDeserializationSchema(SCHEMA, tableOptions);
        assertThat(actualDeser).isEqualTo(expectedDeser);

        // test serialization
        final RawFormatSerializationSchema expectedSer =
                new RawFormatSerializationSchema(ROW_TYPE.getTypeAt(0), "UTF-16", false);
        SerializationSchema<RowData> actualSer = createSerializationSchema(SCHEMA, tableOptions);
        assertThat(actualSer).isEqualTo(expectedSer);
    }

    @Test
    void testInvalidSchema() {
        ResolvedSchema invalidSchema =
                ResolvedSchema.of(
                        Column.physical("f0", DataTypes.STRING()),
                        Column.physical("f1", DataTypes.BIGINT()));
        String expectedError =
                "The 'raw' format only supports single physical column. "
                        + "However the defined schema contains multiple physical columns: [`f0` STRING, `f1` BIGINT]";

        assertThatThrownBy(() -> createDeserializationSchema(invalidSchema, getBasicOptions()))
                .hasMessage(expectedError);

        assertThatThrownBy(() -> createSerializationSchema(invalidSchema, getBasicOptions()))
                .hasMessage(expectedError);
    }

    @Test
    void testInvalidCharset() {
        final Map<String, String> tableOptions =
                getModifiedOptions(
                        options -> {
                            options.put("raw.charset", "UNKNOWN");
                        });

        String expectedError = "Unsupported 'raw.charset' name: UNKNOWN.";

        assertThatThrownBy(() -> createDeserializationSchema(SCHEMA, tableOptions))
                .satisfies(anyCauseMatches(expectedError));

        assertThatThrownBy(() -> createSerializationSchema(SCHEMA, tableOptions))
                .satisfies(anyCauseMatches(expectedError));
    }

    @Test
    void testInvalidEndianness() {
        final Map<String, String> tableOptions =
                getModifiedOptions(
                        options -> {
                            options.put("raw.endianness", "BIG_ENDIAN");
                        });

        String expectedError =
                "Unsupported endianness name: BIG_ENDIAN. "
                        + "Valid values of 'raw.endianness' option are 'big-endian' and 'little-endian'.";

        assertThatThrownBy(() -> createDeserializationSchema(SCHEMA, tableOptions))
                .satisfies(anyCauseMatches(expectedError));

        assertThatThrownBy(() -> createSerializationSchema(SCHEMA, tableOptions))
                .satisfies(anyCauseMatches(expectedError));
    }

    @Test
    void testInvalidFieldTypes() {
        assertThatThrownBy(
                        () ->
                                createDeserializationSchema(
                                        ResolvedSchema.of(
                                                Column.physical("field1", DataTypes.TIMESTAMP(3))),
                                        getBasicOptions()))
                .hasMessage("The 'raw' format doesn't supports 'TIMESTAMP(3)' as column type.");

        assertThatThrownBy(
                        () ->
                                createDeserializationSchema(
                                        ResolvedSchema.of(
                                                Column.physical(
                                                        "field1",
                                                        DataTypes.MAP(
                                                                DataTypes.INT(),
                                                                DataTypes.STRING()))),
                                        getBasicOptions()))
                .hasMessage("The 'raw' format doesn't supports 'MAP<INT, STRING>' as column type.");
    }

    // ------------------------------------------------------------------------
    //  Utilities
    // ------------------------------------------------------------------------

    private static DeserializationSchema<RowData> createDeserializationSchema(
            ResolvedSchema schema, Map<String, String> options) {
        final DynamicTableSource actualSource = createTableSource(schema, options);
        assertThat(actualSource).isInstanceOf(TestDynamicTableFactory.DynamicTableSourceMock.class);
        TestDynamicTableFactory.DynamicTableSourceMock scanSourceMock =
                (TestDynamicTableFactory.DynamicTableSourceMock) actualSource;

        return scanSourceMock.valueFormat.createRuntimeDecoder(
                ScanRuntimeProviderContext.INSTANCE, schema.toPhysicalRowDataType());
    }

    private static SerializationSchema<RowData> createSerializationSchema(
            ResolvedSchema schema, Map<String, String> options) {
        final DynamicTableSink actualSink = createTableSink(schema, options);
        assertThat(actualSink).isInstanceOf(TestDynamicTableFactory.DynamicTableSinkMock.class);
        TestDynamicTableFactory.DynamicTableSinkMock sinkMock =
                (TestDynamicTableFactory.DynamicTableSinkMock) actualSink;

        return sinkMock.valueFormat.createRuntimeEncoder(
                new SinkRuntimeProviderContext(false), schema.toPhysicalRowDataType());
    }

    /**
     * Returns the full options modified by the given consumer {@code optionModifier}.
     *
     * @param optionModifier Consumer to modify the options
     */
    private Map<String, String> getModifiedOptions(Consumer<Map<String, String>> optionModifier) {
        Map<String, String> options = getBasicOptions();
        optionModifier.accept(options);
        return options;
    }

    private Map<String, String> getBasicOptions() {
        final Map<String, String> options = new HashMap<>();
        options.put("connector", TestDynamicTableFactory.IDENTIFIER);
        options.put("target", "MyTarget");
        options.put("buffer-size", "1000");

        options.put("format", RawFormatFactory.IDENTIFIER);
        return options;
    }
}
