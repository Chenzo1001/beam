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
package org.apache.beam.sdk.extensions.avro.schemas.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.JDBCType;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.Conversions;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema.Type;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.reflect.ReflectData;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.avro.util.Utf8;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.avro.coders.AvroCoder;
import org.apache.beam.sdk.extensions.avro.io.AvroGeneratedUser;
import org.apache.beam.sdk.extensions.avro.io.AvroGeneratedUserFactory;
import org.apache.beam.sdk.extensions.avro.schemas.logicaltypes.LogicalTypesExample;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.logicaltypes.EnumerationType;
import org.apache.beam.sdk.schemas.logicaltypes.OneOfType;
import org.apache.beam.sdk.schemas.logicaltypes.SqlTypes;
import org.apache.beam.sdk.testing.CoderProperties;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.util.SerializableUtils;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Lists;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.DateTime;
import org.joda.time.DateTimeFieldType;
import org.joda.time.DateTimeZone;
import org.joda.time.Days;
import org.joda.time.Instant;
import org.joda.time.LocalTime;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for conversion between AVRO records and Beam rows. */
@RunWith(JUnitQuickcheck.class)
@SuppressWarnings({
  "rawtypes", // TODO(https://github.com/apache/beam/issues/20447)
})
public class AvroUtilsTest {

  private static final org.apache.avro.Schema NULL_SCHEMA =
      org.apache.avro.Schema.create(Type.NULL);

  private static final String VERSION_AVRO =
      org.apache.avro.Schema.class.getPackage().getImplementationVersion();

  private Iterable<?> randomData(org.apache.avro.Schema schema, int maxLength) throws Exception {
    Iterable<?> data;
    if (VERSION_AVRO.equals("1.8.2")) {
      data =
          (Iterable<?>)
              Class.forName("org.apache.avro.RandomData")
                  .getDeclaredConstructor(org.apache.avro.Schema.class, Integer.TYPE)
                  .newInstance(schema, maxLength);
    } else {
      data =
          (Iterable<?>)
              Class.forName("org.apache.avro.util.RandomData")
                  .getDeclaredConstructor(org.apache.avro.Schema.class, Integer.TYPE, Boolean.TYPE)
                  // force Utf8 in random data to match with String type used in AvroUtils
                  .newInstance(schema, maxLength, true);
    }
    return data;
  }

  @Property(trials = 1000)
  @SuppressWarnings("unchecked")
  public void supportsAnyAvroSchema(
      @From(AvroGenerators.RecordSchemaGenerator.class) org.apache.avro.Schema avroSchema)
      throws Exception {

    Schema schema = AvroUtils.toBeamSchema(avroSchema);
    Iterable<?> iterable = randomData(avroSchema, 10);

    List<GenericRecord> records = Lists.newArrayList((Iterable<GenericRecord>) iterable);

    for (GenericRecord record : records) {
      AvroUtils.toBeamRowStrict(record, schema);
    }
  }

  @Test
  public void supportsAllLogicalTypes() {

    BigDecimal bigDecimalPrecision5Scale2 = new BigDecimal("123.45");
    BigDecimal bigDecimalPrecision10Scale4 = new BigDecimal("12345.6789");
    BigDecimal bigDecimalPrecision20Scale6 = new BigDecimal("1234567.123456");
    UUID uuid = java.util.UUID.fromString("aa5961a8-a14a-4e8c-91a9-e5d3f35389e8");

    long timestampMicros = 1739543415001000L;
    long timeMicros = 52215000500L;

    DateTime dateTime = new DateTime(2025, 2, 17, 0, 0, 0, DateTimeZone.UTC);

    SpecificRecordBase genericRecord =
        getSpecificRecordWithLogicalTypes(
            dateTime,
            timeMicros,
            timestampMicros,
            bigDecimalPrecision5Scale2,
            bigDecimalPrecision10Scale4,
            bigDecimalPrecision20Scale6,
            uuid);

    Row expected =
        getRowWithLogicalTypes(
            dateTime,
            timeMicros,
            timestampMicros,
            bigDecimalPrecision5Scale2,
            bigDecimalPrecision10Scale4,
            bigDecimalPrecision20Scale6,
            uuid);

    GenericData genericData;
    switch (VERSION_AVRO) {
      case "1.8.2":
        // SpecificRecords generated with 1.8.2 have no registered conversions. Still this is a
        // supported case, as the user can pass a GenericData with the appropriate conversions to
        // AvroUtils.toBeamRowStrict.
        // Basically GenericRecords can contain objects of any type, as long as the user provides
        // the appropriate conversions.
        genericData = new GenericData();
        genericData.addLogicalTypeConversion(new AvroJodaTimeConversions.DateConversion());
        genericData.addLogicalTypeConversion(new AvroJodaTimeConversions.TimeConversion());
        genericData.addLogicalTypeConversion(new AvroJodaTimeConversions.TimestampConversion());
        genericData.addLogicalTypeConversion(new Conversions.DecimalConversion());
        break;
      case "1.9.2":
        // SpecificRecords generated with 1.9.2 have some registered conversions, but not all. We
        // can add the missing ones manually.
        genericData = AvroUtils.getGenericData(genericRecord);
        genericData.addLogicalTypeConversion(new AvroJavaTimeConversions.TimeMicrosConversion());
        genericData.addLogicalTypeConversion(
            new AvroJavaTimeConversions.TimestampMicrosConversion());
        break;
      default:
        // SpecificRecords generated with 1.10.0+ have all conversions registered. Passing null to
        // toBeamRowStrict ensures that the GenericData of the record is used as is.
        genericData = null;
    }

    Row actual = AvroUtils.toBeamRowStrict(genericRecord, null, genericData);

    assertEquals(expected, actual);
  }

  private static Row getRowWithLogicalTypes(
      DateTime dateTime,
      long timeMicros,
      long timestampMicros,
      BigDecimal bigDecimalPrecision5Scale2,
      BigDecimal bigDecimalPrecision10Scale4,
      BigDecimal bigDecimalPrecision20Scale6,
      UUID uuid) {
    return Row.withSchema(AvroUtils.toBeamSchema(LogicalTypesExample.getClassSchema()))
        .withFieldValue("dateField", dateTime)
        .withFieldValue("timeMillisField", (int) (timeMicros / 1000))
        .withFieldValue("timeMicrosField", timeMicros)
        .withFieldValue("timestampMillisField", jodaInstant(timestampMicros))
        .withFieldValue("timestampMicrosField", timestampMicros)
        .withFieldValue("localTimestampMillisField", timestampMicros / 1000)
        .withFieldValue("localTimestampMicrosField", timestampMicros)
        .withFieldValue("decimalSmall", bigDecimalPrecision5Scale2)
        .withFieldValue("decimalMedium", bigDecimalPrecision10Scale4)
        .withFieldValue("decimalLarge", bigDecimalPrecision20Scale6)
        .withFieldValue("fixedDecimalSmall", bigDecimalPrecision5Scale2)
        .withFieldValue("fixedDecimalMedium", bigDecimalPrecision10Scale4)
        .withFieldValue("fixedDecimalLarge", bigDecimalPrecision20Scale6)
        .withFieldValue("uuidField", uuid.toString())
        .build();
  }

  private static LogicalTypesExample getSpecificRecordWithLogicalTypes(
      org.joda.time.DateTime dateTime,
      long timeMicros,
      long timestampMicros,
      BigDecimal bigDecimalPrecision5Scale2,
      BigDecimal bigDecimalPrecision10Scale4,
      BigDecimal bigDecimalPrecision20Scale6,
      UUID uuid) {

    java.time.LocalDate localDate =
        java.time.LocalDate.of(
            dateTime.get(DateTimeFieldType.year()),
            dateTime.get(DateTimeFieldType.monthOfYear()),
            dateTime.get(DateTimeFieldType.dayOfMonth()));
    LogicalTypesExample r = new LogicalTypesExample();

    if (VERSION_AVRO.equals("1.8.2")) {
      // Avro 1.8.2 does not support java.time, must use joda time
      r.put("dateField", dateTime.toLocalDate());
      r.put("timeMillisField", jodaLocalTime(timeMicros));
      r.put("timeMicrosField", timeMicros);
      r.put("timestampMillisField", jodaInstant(timestampMicros).toDateTime());
      r.put("timestampMicrosField", timestampMicros);
    } else {
      r.put("dateField", localDate);
      r.put("timeMillisField", javaLocalTime(timeMicros, ChronoUnit.MILLIS));
      r.put("timeMicrosField", javaLocalTime(timeMicros, ChronoUnit.MICROS));
      r.put("timestampMillisField", javaInstant(timestampMicros, ChronoUnit.MILLIS));
      r.put("timestampMicrosField", javaInstant(timestampMicros, ChronoUnit.MICROS));
    }
    if (VERSION_AVRO.equals("1.8.2") || VERSION_AVRO.equals("1.9.2")) {
      // local-timestamp-millis and local-timestamp-micros only in 1.10.0+
      r.put("localTimestampMillisField", timestampMicros / 1000);
      r.put("localTimestampMicrosField", timestampMicros);
    } else {
      r.put(
          "localTimestampMillisField", javaLocalDateTimeAtUtc(timestampMicros, ChronoUnit.MILLIS));
      r.put(
          "localTimestampMicrosField", javaLocalDateTimeAtUtc(timestampMicros, ChronoUnit.MICROS));
    }

    r.put("decimalSmall", bigDecimalPrecision5Scale2);
    r.put("decimalMedium", bigDecimalPrecision10Scale4);
    r.put("decimalLarge", bigDecimalPrecision20Scale6);
    r.put("fixedDecimalSmall", bigDecimalPrecision5Scale2);
    r.put("fixedDecimalMedium", bigDecimalPrecision10Scale4);
    r.put("fixedDecimalLarge", bigDecimalPrecision20Scale6);

    try {
      r.put("uuidField", uuid.toString());
    } catch (ClassCastException e) {
      // the avro tools version used by gradle-avro-plugin is more recent and uses UUID, while the
      // ones used for backward compatibility tests (1.8.2, 1.9.2 and 1.10.2) use CharSequence
      r.put("uuidField", uuid);
    }

    return r;
  }

  @Property(trials = 1000)
  @SuppressWarnings("unchecked")
  public void avroToBeamRoundTrip(
      @From(AvroGenerators.RecordSchemaGenerator.class) org.apache.avro.Schema avroSchema)
      throws Exception {

    Schema schema = AvroUtils.toBeamSchema(avroSchema);
    Iterable iterable = randomData(avroSchema, 10);
    List<GenericRecord> records = Lists.newArrayList((Iterable<GenericRecord>) iterable);

    for (GenericRecord record : records) {
      Row row = AvroUtils.toBeamRowStrict(record, schema);
      GenericRecord out = AvroUtils.toGenericRecord(row, avroSchema);
      assertEquals(record, out);
    }
  }

  @Test
  public void testUnwrapNullableSchema() {
    org.apache.avro.Schema avroSchema =
        org.apache.avro.Schema.createUnion(
            org.apache.avro.Schema.create(Type.NULL), org.apache.avro.Schema.create(Type.STRING));

    AvroUtils.TypeWithNullability typeWithNullability =
        new AvroUtils.TypeWithNullability(avroSchema);
    assertTrue(typeWithNullability.nullable);
    assertEquals(org.apache.avro.Schema.create(Type.STRING), typeWithNullability.type);
  }

  @Test
  public void testUnwrapNullableSchemaReordered() {
    org.apache.avro.Schema avroSchema =
        org.apache.avro.Schema.createUnion(
            org.apache.avro.Schema.create(Type.STRING), org.apache.avro.Schema.create(Type.NULL));

    AvroUtils.TypeWithNullability typeWithNullability =
        new AvroUtils.TypeWithNullability(avroSchema);
    assertTrue(typeWithNullability.nullable);
    assertEquals(org.apache.avro.Schema.create(Type.STRING), typeWithNullability.type);
  }

  @Test
  public void testUnwrapNullableSchemaToUnion() {
    org.apache.avro.Schema avroSchema =
        org.apache.avro.Schema.createUnion(
            org.apache.avro.Schema.create(Type.STRING),
            org.apache.avro.Schema.create(Type.LONG),
            org.apache.avro.Schema.create(Type.NULL));

    AvroUtils.TypeWithNullability typeWithNullability =
        new AvroUtils.TypeWithNullability(avroSchema);
    assertTrue(typeWithNullability.nullable);
    assertEquals(
        org.apache.avro.Schema.createUnion(
            org.apache.avro.Schema.create(Type.STRING), org.apache.avro.Schema.create(Type.LONG)),
        typeWithNullability.type);
  }

  @Test
  public void testNullableArrayFieldToBeamArrayField() {
    org.apache.avro.Schema.Field avroField =
        new org.apache.avro.Schema.Field(
            "arrayField",
            ReflectData.makeNullable(
                org.apache.avro.Schema.createArray(org.apache.avro.Schema.create(Type.INT))),
            "",
            (Object) null);

    Field expectedBeamField = Field.nullable("arrayField", FieldType.array(FieldType.INT32));

    Field beamField = AvroUtils.toBeamField(avroField);
    assertEquals(expectedBeamField, beamField);
  }

  @Test
  public void testNullableBeamArrayFieldToAvroField() {
    Field beamField = Field.nullable("arrayField", FieldType.array(FieldType.INT32));

    org.apache.avro.Schema.Field expectedAvroField =
        new org.apache.avro.Schema.Field(
            "arrayField",
            ReflectData.makeNullable(
                org.apache.avro.Schema.createArray(org.apache.avro.Schema.create(Type.INT))),
            "",
            (Object) null);

    org.apache.avro.Schema.Field avroField = AvroUtils.toAvroField(beamField, "ignored");
    assertEquals(expectedAvroField, avroField);
  }

  private static List<org.apache.avro.Schema.Field> getAvroSubSchemaFields() {
    List<org.apache.avro.Schema.Field> fields = Lists.newArrayList();
    fields.add(
        new org.apache.avro.Schema.Field(
            "bool", org.apache.avro.Schema.create(Type.BOOLEAN), "", (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "int", org.apache.avro.Schema.create(Type.INT), "", (Object) null));
    return fields;
  }

  private static org.apache.avro.Schema getAvroSubSchema(String name) {
    return org.apache.avro.Schema.createRecord(
        name, null, "topLevelRecord", false, getAvroSubSchemaFields());
  }

  private static org.apache.avro.Schema getAvroSchema() {
    List<org.apache.avro.Schema.Field> fields = Lists.newArrayList();
    fields.add(
        new org.apache.avro.Schema.Field(
            "bool", org.apache.avro.Schema.create(Type.BOOLEAN), "", (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "int", org.apache.avro.Schema.create(Type.INT), "", (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "long", org.apache.avro.Schema.create(Type.LONG), "", (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "float", org.apache.avro.Schema.create(Type.FLOAT), "", (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "double", org.apache.avro.Schema.create(Type.DOUBLE), "", (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "string", org.apache.avro.Schema.create(Type.STRING), "", (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "bytes", org.apache.avro.Schema.create(Type.BYTES), "", (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "decimal",
            LogicalTypes.decimal(Integer.MAX_VALUE)
                .addToSchema(org.apache.avro.Schema.create(Type.BYTES)),
            "",
            (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "timestampMillis",
            LogicalTypes.timestampMillis().addToSchema(org.apache.avro.Schema.create(Type.LONG)),
            "",
            (Object) null));
    fields.add(new org.apache.avro.Schema.Field("row", getAvroSubSchema("row"), "", (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "array",
            org.apache.avro.Schema.createArray(getAvroSubSchema("array")),
            "",
            (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "map", org.apache.avro.Schema.createMap(getAvroSubSchema("map")), "", (Object) null));
    return org.apache.avro.Schema.createRecord("topLevelRecord", null, null, false, fields);
  }

  private static Schema getBeamSubSchema() {
    return new Schema.Builder()
        .addField(Field.of("bool", FieldType.BOOLEAN))
        .addField(Field.of("int", FieldType.INT32))
        .build();
  }

  private Schema getBeamSchema() {
    Schema subSchema = getBeamSubSchema();
    return new Schema.Builder()
        .addField(Field.of("bool", FieldType.BOOLEAN))
        .addField(Field.of("int", FieldType.INT32))
        .addField(Field.of("long", FieldType.INT64))
        .addField(Field.of("float", FieldType.FLOAT))
        .addField(Field.of("double", FieldType.DOUBLE))
        .addField(Field.of("string", FieldType.STRING))
        .addField(Field.of("bytes", FieldType.BYTES))
        .addField(Field.of("decimal", FieldType.DECIMAL))
        .addField(Field.of("timestampMillis", FieldType.DATETIME))
        .addField(Field.of("row", FieldType.row(subSchema)))
        .addField(Field.of("array", FieldType.array(FieldType.row(subSchema))))
        .addField(Field.of("map", FieldType.map(FieldType.STRING, FieldType.row(subSchema))))
        .build();
  }

  private static final byte[] BYTE_ARRAY = new byte[] {1, 2, 3, 4};
  private static final DateTime DATE_TIME =
      new DateTime().withDate(1979, 3, 14).withTime(1, 2, 3, 4).withZone(DateTimeZone.UTC);
  private static final BigDecimal BIG_DECIMAL = new BigDecimal(3600);

  private Row getBeamRow() {
    Row subRow = Row.withSchema(getBeamSubSchema()).addValues(true, 42).build();
    return Row.withSchema(getBeamSchema())
        .addValue(true)
        .addValue(43)
        .addValue(44L)
        .addValue((float) 44.1)
        .addValue((double) 44.2)
        .addValue("string")
        .addValue(BYTE_ARRAY)
        .addValue(BIG_DECIMAL)
        .addValue(DATE_TIME)
        .addValue(subRow)
        .addValue(ImmutableList.of(subRow, subRow))
        .addValue(ImmutableMap.of("k1", subRow, "k2", subRow))
        .build();
  }

  private static GenericRecord getSubGenericRecord(String name) {
    return new GenericRecordBuilder(getAvroSubSchema(name))
        .set("bool", true)
        .set("int", 42)
        .build();
  }

  private static GenericRecord getGenericRecord() {

    LogicalType decimalType =
        LogicalTypes.decimal(Integer.MAX_VALUE)
            .addToSchema(org.apache.avro.Schema.create(Type.BYTES))
            .getLogicalType();
    ByteBuffer encodedDecimal =
        new Conversions.DecimalConversion().toBytes(BIG_DECIMAL, null, decimalType);

    return new GenericRecordBuilder(getAvroSchema())
        .set("bool", true)
        .set("int", 43)
        .set("long", 44L)
        .set("float", (float) 44.1)
        .set("double", (double) 44.2)
        .set("string", new Utf8("string"))
        .set("bytes", ByteBuffer.wrap(BYTE_ARRAY))
        .set("decimal", encodedDecimal)
        .set("timestampMillis", DATE_TIME.getMillis())
        .set("row", getSubGenericRecord("row"))
        .set("array", ImmutableList.of(getSubGenericRecord("array"), getSubGenericRecord("array")))
        .set(
            "map",
            ImmutableMap.of(
                new Utf8("k1"),
                getSubGenericRecord("map"),
                new Utf8("k2"),
                getSubGenericRecord("map")))
        .build();
  }

  private static java.time.Instant javaInstant(long micros, TemporalUnit temporalUnit) {
    return java.time.Instant.ofEpochSecond(micros / 1000000, micros * 1000 % 1000000000)
        .truncatedTo(temporalUnit);
  }

  private static java.time.LocalDateTime javaLocalDateTimeAtUtc(
      long micros, TemporalUnit temporalUnit) {
    return javaInstant(micros, temporalUnit).atOffset(java.time.ZoneOffset.UTC).toLocalDateTime();
  }

  private static org.joda.time.Instant jodaInstant(long micros) {
    return org.joda.time.Instant.ofEpochMilli(micros / 1000);
  }

  private static java.time.LocalTime javaLocalTime(long micros, TemporalUnit temporalUnit) {
    return java.time.LocalTime.ofNanoOfDay(micros * 1000).truncatedTo(temporalUnit);
  }

  private static org.joda.time.LocalTime jodaLocalTime(long micros) {
    return org.joda.time.LocalTime.fromMillisOfDay(micros / 1000);
  }

  @Test
  public void testFromAvroSchema() {
    assertEquals(getBeamSchema(), AvroUtils.toBeamSchema(getAvroSchema()));
  }

  @Test
  public void testFromBeamSchema() {
    Schema beamSchema = getBeamSchema();
    org.apache.avro.Schema avroSchema = AvroUtils.toAvroSchema(beamSchema);
    assertEquals(getAvroSchema(), avroSchema);
  }

  @Test
  public void testAvroSchemaFromBeamSchemaCanBeParsed() {
    org.apache.avro.Schema convertedSchema = AvroUtils.toAvroSchema(getBeamSchema());
    org.apache.avro.Schema validatedSchema =
        new org.apache.avro.Schema.Parser().parse(convertedSchema.toString());
    assertEquals(convertedSchema, validatedSchema);
  }

  @Test
  public void testAvroSchemaFromBeamSchemaWithFieldCollisionCanBeParsed() {

    // Two similar schemas, the only difference is the "street" field type in the nested record.
    Schema contact =
        new Schema.Builder()
            .addField(Field.of("name", FieldType.STRING))
            .addField(
                Field.of(
                    "address",
                    FieldType.row(
                        new Schema.Builder()
                            .addField(Field.of("street", FieldType.STRING))
                            .addField(Field.of("city", FieldType.STRING))
                            .build())))
            .build();

    Schema contactMultiline =
        new Schema.Builder()
            .addField(Field.of("name", FieldType.STRING))
            .addField(
                Field.of(
                    "address",
                    FieldType.row(
                        new Schema.Builder()
                            .addField(Field.of("street", FieldType.array(FieldType.STRING)))
                            .addField(Field.of("city", FieldType.STRING))
                            .build())))
            .build();

    // Ensure that no collisions happen between two sibling fields with same-named child fields
    // (with different schemas, between a parent field and a sub-record field with the same name,
    // and artificially with the generated field name.
    Schema beamSchema =
        new Schema.Builder()
            .addField(Field.of("home", FieldType.row(contact)))
            .addField(Field.of("work", FieldType.row(contactMultiline)))
            .addField(Field.of("address", FieldType.row(contact)))
            .addField(Field.of("topLevelRecord", FieldType.row(contactMultiline)))
            .build();

    org.apache.avro.Schema convertedSchema = AvroUtils.toAvroSchema(beamSchema);
    org.apache.avro.Schema validatedSchema =
        new org.apache.avro.Schema.Parser().parse(convertedSchema.toString());
    assertEquals(convertedSchema, validatedSchema);
  }

  @Test
  public void testNullableFieldInAvroSchema() {
    List<org.apache.avro.Schema.Field> fields = Lists.newArrayList();
    fields.add(
        new org.apache.avro.Schema.Field(
            "int",
            ReflectData.makeNullable(org.apache.avro.Schema.create(Type.INT)),
            "",
            (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "array",
            org.apache.avro.Schema.createArray(
                ReflectData.makeNullable(org.apache.avro.Schema.create(Type.BYTES))),
            "",
            (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "map",
            org.apache.avro.Schema.createMap(
                ReflectData.makeNullable(org.apache.avro.Schema.create(Type.INT))),
            "",
            (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "enum",
            ReflectData.makeNullable(
                org.apache.avro.Schema.createEnum(
                    "fruit", "", "", ImmutableList.of("banana", "apple", "pear"))),
            "",
            (Object) null));

    org.apache.avro.Schema avroSchema =
        org.apache.avro.Schema.createRecord("topLevelRecord", null, null, false, fields);

    Schema expectedSchema =
        Schema.builder()
            .addNullableField("int", FieldType.INT32)
            .addArrayField("array", FieldType.BYTES.withNullable(true))
            .addMapField("map", FieldType.STRING, FieldType.INT32.withNullable(true))
            .addField(
                "enum",
                FieldType.logicalType(EnumerationType.create("banana", "apple", "pear"))
                    .withNullable(true))
            .build();
    assertEquals(expectedSchema, AvroUtils.toBeamSchema(avroSchema));

    Map<String, Object> nullMap = Maps.newHashMap();
    nullMap.put("k1", null);
    GenericRecord genericRecord =
        new GenericRecordBuilder(avroSchema)
            .set("int", null)
            .set("array", Lists.newArrayList((Object) null))
            .set("map", nullMap)
            .set("enum", null)
            .build();
    Row expectedRow =
        Row.withSchema(expectedSchema)
            .addValue(null)
            .addValue(Lists.newArrayList((Object) null))
            .addValue(nullMap)
            .addValue(null)
            .build();
    assertEquals(expectedRow, AvroUtils.toBeamRowStrict(genericRecord, expectedSchema));
  }

  @Test
  public void testNullableFieldsInBeamSchema() {
    Schema beamSchema =
        Schema.builder()
            .addNullableField("int", FieldType.INT32)
            .addArrayField("array", FieldType.INT32.withNullable(true))
            .addMapField("map", FieldType.STRING, FieldType.INT32.withNullable(true))
            .build();

    List<org.apache.avro.Schema.Field> fields = Lists.newArrayList();
    fields.add(
        new org.apache.avro.Schema.Field(
            "int",
            ReflectData.makeNullable(org.apache.avro.Schema.create(Type.INT)),
            "",
            (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "array",
            org.apache.avro.Schema.createArray(
                ReflectData.makeNullable(org.apache.avro.Schema.create(Type.INT))),
            "",
            (Object) null));
    fields.add(
        new org.apache.avro.Schema.Field(
            "map",
            org.apache.avro.Schema.createMap(
                ReflectData.makeNullable(org.apache.avro.Schema.create(Type.INT))),
            "",
            (Object) null));
    org.apache.avro.Schema avroSchema =
        org.apache.avro.Schema.createRecord("topLevelRecord", null, null, false, fields);
    assertEquals(avroSchema, AvroUtils.toAvroSchema(beamSchema));

    Map<Utf8, Object> nullMapUtf8 = Maps.newHashMap();
    nullMapUtf8.put(new Utf8("k1"), null);
    Map<String, Object> nullMapString = Maps.newHashMap();
    nullMapString.put("k1", null);

    GenericRecord expectedGenericRecord =
        new GenericRecordBuilder(avroSchema)
            .set("int", null)
            .set("array", Lists.newArrayList((Object) null))
            .set("map", nullMapUtf8)
            .build();
    Row row =
        Row.withSchema(beamSchema)
            .addValue(null)
            .addValue(Lists.newArrayList((Object) null))
            .addValue(nullMapString)
            .build();
    assertEquals(expectedGenericRecord, AvroUtils.toGenericRecord(row, avroSchema));
  }

  @Test
  public void testUnionFieldInAvroSchema() {

    List<org.apache.avro.Schema.Field> fields = Lists.newArrayList();
    List<org.apache.avro.Schema> unionFields = Lists.newArrayList();

    unionFields.add(org.apache.avro.Schema.create(Type.INT));
    unionFields.add(org.apache.avro.Schema.create(Type.STRING));

    fields.add(
        new org.apache.avro.Schema.Field(
            "union", org.apache.avro.Schema.createUnion(unionFields), "", (Object) null));
    org.apache.avro.Schema avroSchema =
        org.apache.avro.Schema.createRecord("topLevelRecord", null, null, false, fields);
    OneOfType oneOfType =
        OneOfType.create(Field.of("int", FieldType.INT32), Field.of("string", FieldType.STRING));

    Schema expectedSchema = Schema.builder().addLogicalTypeField("union", oneOfType).build();
    assertEquals(expectedSchema, AvroUtils.toBeamSchema(avroSchema));
    GenericRecord genericRecord = new GenericRecordBuilder(avroSchema).set("union", 23423).build();
    Row expectedRow =
        Row.withSchema(expectedSchema).addValue(oneOfType.createValue(0, 23423)).build();
    assertEquals(expectedRow, AvroUtils.toBeamRowStrict(genericRecord, expectedSchema));
  }

  @Test
  public void testUnionFieldInBeamSchema() {
    OneOfType oneOfType =
        OneOfType.create(Field.of("int", FieldType.INT32), Field.of("string", FieldType.STRING));

    Schema beamSchema = Schema.builder().addLogicalTypeField("union", oneOfType).build();
    List<org.apache.avro.Schema.Field> fields = Lists.newArrayList();
    List<org.apache.avro.Schema> unionFields = Lists.newArrayList();

    unionFields.add(org.apache.avro.Schema.create(Type.INT));
    unionFields.add(org.apache.avro.Schema.create(Type.STRING));
    fields.add(
        new org.apache.avro.Schema.Field(
            "union", org.apache.avro.Schema.createUnion(unionFields), "", (Object) null));
    org.apache.avro.Schema avroSchema =
        org.apache.avro.Schema.createRecord("topLevelRecord", null, null, false, fields);
    GenericRecord expectedGenericRecord =
        new GenericRecordBuilder(avroSchema).set("union", 23423).build();
    Row row = Row.withSchema(beamSchema).addValue(oneOfType.createValue(0, 23423)).build();
    assertEquals(expectedGenericRecord, AvroUtils.toGenericRecord(row, avroSchema));
  }

  @Test
  public void testJdbcLogicalVarCharRowDataToAvroSchema() {
    String expectedAvroSchemaJson =
        "{ "
            + " \"name\": \"topLevelRecord\", "
            + " \"type\": \"record\", "
            + " \"fields\": [{ "
            + "   \"name\": \"my_varchar_field\", "
            + "   \"type\": {\"type\": \"string\", \"logicalType\": \"varchar\", \"maxLength\": 10}"
            + "  }, "
            + "  { "
            + "   \"name\": \"my_longvarchar_field\", "
            + "   \"type\": {\"type\": \"string\", \"logicalType\": \"varchar\", \"maxLength\": 50}"
            + "  }, "
            + "  { "
            + "   \"name\": \"my_nvarchar_field\", "
            + "   \"type\": {\"type\": \"string\", \"logicalType\": \"varchar\", \"maxLength\": 10}"
            + "  }, "
            + "  { "
            + "   \"name\": \"my_longnvarchar_field\", "
            + "   \"type\": {\"type\": \"string\", \"logicalType\": \"varchar\", \"maxLength\": 50}"
            + "  }, "
            + "  { "
            + "   \"name\": \"fixed_length_char_field\", "
            + "   \"type\": {\"type\": \"string\", \"logicalType\": \"char\", \"maxLength\": 25}"
            + "  } "
            + " ] "
            + "}";

    Schema beamSchema =
        Schema.builder()
            .addField(
                Field.of(
                    "my_varchar_field", FieldType.logicalType(JdbcType.StringType.varchar(10))))
            .addField(
                Field.of(
                    "my_longvarchar_field",
                    FieldType.logicalType(JdbcType.StringType.longvarchar(50))))
            .addField(
                Field.of(
                    "my_nvarchar_field", FieldType.logicalType(JdbcType.StringType.nvarchar(10))))
            .addField(
                Field.of(
                    "my_longnvarchar_field",
                    FieldType.logicalType(JdbcType.StringType.longnvarchar(50))))
            .addField(
                Field.of(
                    "fixed_length_char_field",
                    FieldType.logicalType(JdbcType.StringType.fixedLengthChar(25))))
            .build();

    assertEquals(
        new org.apache.avro.Schema.Parser().parse(expectedAvroSchemaJson),
        AvroUtils.toAvroSchema(beamSchema));
  }

  @Test
  public void testJdbcLogicalVarCharRowDataToGenericRecord() {
    Schema beamSchema =
        Schema.builder()
            .addField(
                Field.of(
                    "my_varchar_field", FieldType.logicalType(JdbcType.StringType.varchar(10))))
            .addField(
                Field.of(
                    "my_longvarchar_field",
                    FieldType.logicalType(JdbcType.StringType.longvarchar(50))))
            .addField(
                Field.of(
                    "my_nvarchar_field", FieldType.logicalType(JdbcType.StringType.nvarchar(10))))
            .addField(
                Field.of(
                    "my_longnvarchar_field",
                    FieldType.logicalType(JdbcType.StringType.longnvarchar(50))))
            .build();

    Row rowData =
        Row.withSchema(beamSchema)
            .addValue("varchar_value")
            .addValue("longvarchar_value")
            .addValue("nvarchar_value")
            .addValue("longnvarchar_value")
            .build();

    org.apache.avro.Schema avroSchema = AvroUtils.toAvroSchema(beamSchema);
    GenericRecord expectedRecord =
        new GenericRecordBuilder(avroSchema)
            .set("my_varchar_field", "varchar_value")
            .set("my_longvarchar_field", "longvarchar_value")
            .set("my_nvarchar_field", "nvarchar_value")
            .set("my_longnvarchar_field", "longnvarchar_value")
            .build();

    assertEquals(expectedRecord, AvroUtils.toGenericRecord(rowData, avroSchema));
  }

  @Test
  public void testJdbcLogicalDateAndTimeRowDataToAvroSchema() {
    String expectedAvroSchemaJson =
        "{ "
            + " \"name\": \"topLevelRecord\", "
            + " \"type\": \"record\", "
            + " \"fields\": [{ "
            + "   \"name\": \"my_date_field\", "
            + "   \"type\": { \"type\": \"int\", \"logicalType\": \"date\" }"
            + "  }, "
            + "  { "
            + "   \"name\": \"my_time_field\", "
            + "   \"type\": { \"type\": \"int\", \"logicalType\": \"time-millis\" }"
            + "  }"
            + " ] "
            + "}";

    Schema beamSchema =
        Schema.builder()
            .addField(Field.of("my_date_field", FieldType.logicalType(JdbcType.DATE)))
            .addField(Field.of("my_time_field", FieldType.logicalType(JdbcType.TIME)))
            .build();

    assertEquals(
        new org.apache.avro.Schema.Parser().parse(expectedAvroSchemaJson),
        AvroUtils.toAvroSchema(beamSchema));
  }

  @Test
  public void testJdbcLogicalDateAndTimeRowDataToGenericRecord() {
    // Test Fixed clock at
    DateTime testDateTime = DateTime.parse("2021-05-29T11:15:16.234Z");

    Schema beamSchema =
        Schema.builder()
            .addField(Field.of("my_date_field", FieldType.logicalType(JdbcType.DATE)))
            .addField(Field.of("my_time_field", FieldType.logicalType(JdbcType.TIME)))
            .build();

    Row rowData =
        Row.withSchema(beamSchema)
            .addValue(testDateTime.toLocalDate().toDateTime(LocalTime.MIDNIGHT).toInstant())
            .addValue(Instant.ofEpochMilli(testDateTime.toLocalTime().millisOfDay().get()))
            .build();

    int daysFromEpoch =
        Days.daysBetween(
                Instant.EPOCH,
                testDateTime.toLocalDate().toDateTime(LocalTime.MIDNIGHT).toInstant())
            .getDays();
    int timeSinceMidNight = testDateTime.toLocalTime().getMillisOfDay();

    org.apache.avro.Schema avroSchema = AvroUtils.toAvroSchema(beamSchema);
    GenericRecord expectedRecord =
        new GenericRecordBuilder(avroSchema)
            .set("my_date_field", daysFromEpoch)
            .set("my_time_field", timeSinceMidNight)
            .build();

    assertEquals(expectedRecord, AvroUtils.toGenericRecord(rowData, avroSchema));
  }

  @Test
  public void testSqlTypesToGenericRecord() {
    // SqlTypes to LogicalTypes.date conversion is one direction
    java.time.LocalDate localDate = java.time.LocalDate.of(1979, 3, 14);

    Schema beamSchema =
        Schema.builder()
            .addField(Field.of("local_date", FieldType.logicalType(SqlTypes.DATE)))
            .build();

    Row rowData = Row.withSchema(beamSchema).addValue(localDate).build();

    org.apache.avro.Schema avroSchema = AvroUtils.toAvroSchema(beamSchema);
    GenericRecord expectedRecord =
        new GenericRecordBuilder(avroSchema).set("local_date", localDate.toEpochDay()).build();

    assertEquals(expectedRecord, AvroUtils.toGenericRecord(rowData, avroSchema));
  }

  @Test
  public void testBeamRowToGenericRecord() {
    GenericRecord genericRecord = AvroUtils.toGenericRecord(getBeamRow(), null);
    assertEquals(getAvroSchema(), genericRecord.getSchema());
    assertEquals(getGenericRecord(), genericRecord);
  }

  @Test
  public void testBeamRowToGenericRecordInferSchema() {
    GenericRecord genericRecord = AvroUtils.toGenericRecord(getBeamRow());
    assertEquals(getAvroSchema(), genericRecord.getSchema());
    assertEquals(getGenericRecord(), genericRecord);
  }

  @Test
  public void testRowToGenericRecordFunction() {
    SerializableUtils.ensureSerializable(AvroUtils.getRowToGenericRecordFunction(NULL_SCHEMA));
    SerializableUtils.ensureSerializable(AvroUtils.getRowToGenericRecordFunction(null));
  }

  @Test
  public void testGenericRecordToBeamRow() {
    GenericRecord genericRecord = getGenericRecord();
    Row row = AvroUtils.toBeamRowStrict(getGenericRecord(), null);
    assertEquals(getBeamRow(), row);

    // Alternatively, a timestamp-millis logical type can have a joda datum.
    genericRecord.put("timestampMillis", new DateTime(genericRecord.get("timestampMillis")));
    row = AvroUtils.toBeamRowStrict(getGenericRecord(), null);
    assertEquals(getBeamRow(), row);
  }

  @Test
  public void testGenericRecordToRowFunction() {
    SerializableUtils.ensureSerializable(AvroUtils.getGenericRecordToRowFunction(Schema.of()));
    SerializableUtils.ensureSerializable(AvroUtils.getGenericRecordToRowFunction(null));
  }

  @Test
  public void testAvroSchemaCoders() {
    Pipeline pipeline = Pipeline.create();
    org.apache.avro.Schema schema =
        org.apache.avro.Schema.createRecord(
            "TestSubRecord",
            "TestSubRecord doc",
            "org.apache.beam.sdk.extensions.avro.schemas.utils",
            false,
            getAvroSubSchemaFields());
    GenericRecord record =
        new GenericRecordBuilder(getAvroSubSchema("simple"))
            .set("bool", true)
            .set("int", 42)
            .build();

    PCollection<GenericRecord> records =
        pipeline.apply(Create.of(record).withCoder(AvroCoder.of(schema)));
    assertFalse(records.hasSchema());
    records.setCoder(AvroUtils.schemaCoder(schema));
    assertTrue(records.hasSchema());
    CoderProperties.coderSerializable(records.getCoder());

    AvroGeneratedUser user = AvroGeneratedUserFactory.newInstance("foo", 42, "green");
    PCollection<AvroGeneratedUser> users =
        pipeline.apply(Create.of(user).withCoder(AvroCoder.of(AvroGeneratedUser.class)));
    assertFalse(users.hasSchema());
    users.setCoder(AvroUtils.schemaCoder((AvroCoder<AvroGeneratedUser>) users.getCoder()));
    assertTrue(users.hasSchema());
    CoderProperties.coderSerializable(users.getCoder());
  }

  @Test
  public void testAvroBytesToRowAndRowToAvroBytesFunctions() {
    Schema schema =
        Schema.builder()
            .addInt32Field("f_int")
            .addInt64Field("f_long")
            .addDoubleField("f_double")
            .addStringField("f_string")
            .build();

    SimpleFunction<Row, byte[]> toBytesFn = AvroUtils.getRowToAvroBytesFunction(schema);
    SimpleFunction<byte[], Row> toRowFn = AvroUtils.getAvroBytesToRowFunction(schema);

    Row row = Row.withSchema(schema).attachValues(1, 1L, 1d, "string");

    byte[] serializedRow = toBytesFn.apply(row);
    Row deserializedRow = toRowFn.apply(serializedRow);

    assertEquals(row, deserializedRow);
  }

  @Test
  public void testNullSchemas() {
    assertEquals(
        AvroUtils.getFromRowFunction(GenericRecord.class),
        AvroUtils.getFromRowFunction(GenericRecord.class));
  }

  /** Helper class that simulate JDBC Logical types. */
  private static class JdbcType<T> implements Schema.LogicalType<T, T> {

    private static final JdbcType<Instant> DATE =
        new JdbcType<>(JDBCType.DATE, FieldType.STRING, FieldType.DATETIME, "");
    private static final JdbcType<Instant> TIME =
        new JdbcType<>(JDBCType.TIME, FieldType.STRING, FieldType.DATETIME, "");

    private final String identifier;
    private final FieldType argumentType;
    private final FieldType baseType;
    private final Object argument;

    private static class StringType extends JdbcType<String> {

      private static StringType fixedLengthChar(int size) {
        return new StringType(JDBCType.CHAR, size);
      }

      private static StringType varchar(int size) {
        return new StringType(JDBCType.VARCHAR, size);
      }

      private static StringType longvarchar(int size) {
        return new StringType(JDBCType.LONGVARCHAR, size);
      }

      private static StringType nvarchar(int size) {
        return new StringType(JDBCType.NVARCHAR, size);
      }

      private static StringType longnvarchar(int size) {
        return new StringType(JDBCType.LONGNVARCHAR, size);
      }

      private StringType(JDBCType type, int size) {
        super(type, FieldType.INT32, FieldType.STRING, size);
      }
    }

    private JdbcType(
        JDBCType jdbcType, FieldType argumentType, FieldType baseType, Object argument) {
      this.identifier = jdbcType.getName();
      this.argumentType = argumentType;
      this.baseType = baseType;
      this.argument = argument;
    }

    @Override
    public String getIdentifier() {
      return identifier;
    }

    @Override
    public @Nullable FieldType getArgumentType() {
      return argumentType;
    }

    @Override
    public FieldType getBaseType() {
      return baseType;
    }

    @Override
    @SuppressWarnings("TypeParameterUnusedInFormals")
    public <T1> @Nullable T1 getArgument() {
      return (T1) argument;
    }

    @Override
    public @NonNull T toBaseType(@NonNull T input) {
      return input;
    }

    @Override
    public @NonNull T toInputType(@NonNull T base) {
      return base;
    }
  }
}
