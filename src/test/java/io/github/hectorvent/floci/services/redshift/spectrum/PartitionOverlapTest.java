package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.Table;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionOverlapTest {

    @Test
    void integerKeysThatCastToTheSameValueOverlap() {
        assertTrue(overlap("int", "1", "01"));
        assertTrue(overlap("bigint", "7", "+7"));
        assertFalse(overlap("int", "1", "2"));
    }

    @Test
    void decimalKeysOverlapOnValueAndScale() {
        assertTrue(overlap("decimal(5,2)", "1.0", "1.00"));
        assertTrue(overlap("decimal(5,2)", "1.001", "1.002"));
        assertFalse(overlap("decimal(5,2)", "1.01", "1.02"));
    }

    @Test
    void booleanKeysOverlapOnPostgresSpellings() {
        assertTrue(overlap("boolean", "true", "t"));
        assertTrue(overlap("boolean", "false", "off"));
        assertFalse(overlap("boolean", "true", "false"));
    }

    @Test
    void dateAndTimestampKeysOverlapOnTheirValue() {
        assertTrue(overlap("date", "2026-01-02", "2026-01-02"));
        assertTrue(overlap("timestamp", "2026-01-02 03:04:05", "2026-01-02T03:04:05"));
        assertFalse(overlap("date", "2026-01-02", "2026-01-03"));
    }

    @Test
    void stringKeysOverlapOnlyWhenEqual() {
        assertFalse(overlap("string", "1", "01"));
        assertTrue(overlap("char(3)", "a", "a  "));
    }

    @Test
    void missingAndDefaultPartitionsBothBecomeNull() {
        assertTrue(overlap("string", "__HIVE_DEFAULT_PARTITION__", null));
    }

    @Test
    void aValueThatCannotBeReducedCountsAsAnOverlap() {
        assertTrue(overlap("int", "abc", "1"));
    }

    private static boolean overlap(String type, String first, String second) {
        Column key = new Column();
        key.setName("k");
        key.setType(type);
        Table table = new Table();
        table.setPartitionKeys(List.of(key));
        List<Partition> partitions = new ArrayList<>();
        for (String value : new String[] {first, second}) {
            Partition partition = new Partition();
            List<String> values = new ArrayList<>();
            values.add(value);
            partition.setValues(values);
            partitions.add(partition);
        }
        return ExternalTableMaterializer.hasOverlappingPartitions(table, partitions);
    }
}
