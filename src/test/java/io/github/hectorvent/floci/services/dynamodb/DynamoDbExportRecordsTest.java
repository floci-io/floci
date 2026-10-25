package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPointInTimeRecovery.ItemChange;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamoDbExportRecordsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TableDefinition TABLE = new TableDefinition("Sorted",
            List.of(new KeySchemaElement("zk", "HASH"), new KeySchemaElement("ak", "RANGE")),
            List.of(new AttributeDefinition("zk", "S"), new AttributeDefinition("ak", "N")));
    private static final String ITEM = """
        {"zk": {"S": "old"}, "ak": {"N": "1"}, "😀": {"S": "emoji name"}, "～": {"S": "fullwidth tilde name"},
         "z": {"S": "z"}, "a": {"S": "a"}, "B": {"S": "upper B"}, "é": {"S": "e acute"},
         "ss": {"SS": ["😀", "～", "b", "B", "a", "é"]}, "ns": {"NS": ["3", "-10", "100", "-2.5", "0", "0.25"]},
         "bs": {"BS": ["/w==", "AA==", "gA==", "fw==", "Pg==", "AAE="]},
         "m": {"M": {"z": {"N": "1"}, "a": {"N": "2"}, "😀": {"S": "e"}, "～": {"S": "t"},
                     "nested": {"M": {"y": {"N": "1"}, "b": {"N": "2"}}}}},
         "l": {"L": [{"M": {"z": {"N": "1"}, "a": {"N": "2"}}}, {"SS": ["b", "a"]}, {"NS": ["2", "1"]}]},
         "v": {"S": "%s"}}
        """;
    private static final String SORTED = "\"B\":{\"S\":\"upper B\"},\"a\":{\"S\":\"a\"},\"ak\":{\"N\":\"1\"},"
            + "\"bs\":{\"BS\":[\"AA==\",\"AAE=\",\"Pg==\",\"fw==\",\"gA==\",\"/w==\"]},"
            + "\"l\":{\"L\":[{\"M\":{\"a\":{\"N\":\"2\"},\"z\":{\"N\":\"1\"}}},{\"SS\":[\"a\",\"b\"]},{\"NS\":[\"1\",\"2\"]}]},"
            + "\"m\":{\"M\":{\"a\":{\"N\":\"2\"},\"nested\":{\"M\":{\"b\":{\"N\":\"2\"},\"y\":{\"N\":\"1\"}}},"
            + "\"z\":{\"N\":\"1\"},\"～\":{\"S\":\"t\"},\"😀\":{\"S\":\"e\"}}},"
            + "\"ns\":{\"NS\":[\"-10\",\"-2.5\",\"0\",\"0.25\",\"3\",\"100\"]},"
            + "\"ss\":{\"SS\":[\"B\",\"a\",\"b\",\"é\",\"～\",\"😀\"]},\"v\":{\"S\":\"%s\"},\"z\":{\"S\":\"z\"},"
            + "\"zk\":{\"S\":\"old\"},\"é\":{\"S\":\"e acute\"},\"～\":{\"S\":\"fullwidth tilde name\"},"
            + "\"😀\":{\"S\":\"emoji name\"}";
    private static final String ION_SORTED = "B:\"upper B\",a:\"a\",ak:1.,"
            + "bs:$dynamodb_BS::[{{AA==}},{{AAE=}},{{Pg==}},{{fw==}},{{gA==}},{{/w==}}],"
            + "l:[{a:2.,z:1.},$dynamodb_SS::[\"a\",\"b\"],$dynamodb_NS::[1.,2.]],"
            + "m:{a:2.,nested:{b:2.,y:1.},z:1.,'～':\"t\",'😀':\"e\"},ns:$dynamodb_NS::[-1d1,-2.5,0.,0.25,3.,1d2],"
            + "ss:$dynamodb_SS::[\"B\",\"a\",\"b\",\"é\",\"～\",\"😀\"],v:\"%s\",z:\"z\",zk:\"old\",'é':\"e acute\","
            + "'～':\"fullwidth tilde name\",'😀':\"emoji name\"";

    @Test
    void recordIsTheRecordAwsWrites() throws IOException {
        ItemChange update = new ItemChange(MAPPER.readTree(ITEM.formatted("before")), MAPPER.readTree(ITEM.formatted("after")),
                Instant.EPOCH.plus(1792884188164874L, ChronoUnit.MICROS));

        JsonNode record = DynamoDbExportRecords.records(List.of(update), TABLE, false).getFirst();

        assertEquals("{\"Metadata\":{\"WriteTimestampMicros\":{\"N\":\"1792884188164874\"}},"
                + "\"Keys\":{\"ak\":{\"N\":\"1\"},\"zk\":{\"S\":\"old\"}},\"OldImage\":{" + SORTED.formatted("before")
                + "},\"NewImage\":{" + SORTED.formatted("after") + "}}", MAPPER.writeValueAsString(record));
        assertEquals("$ion_1_0 {Record:{Keys:{ak:1.,zk:\"old\"},Metadata:{WriteTimestampMicros:1792884188164874.},"
                + "OldImage:{" + ION_SORTED.formatted("before") + "},NewImage:{" + ION_SORTED.formatted("after") + "}}}",
                DynamoDbIonText.recordLine(record));
    }

    @Test
    void itemWhoseSetsOnlyChangeOrderGetsNoRecord() throws IOException {
        String item = """
            {"zk": {"S": "k"}, "ak": {"N": "1"}, "ss": {"SS": %s}, "ns": {"NS": %s}, "bs": {"BS": %s},
             "m": {"M": {"ss": {"SS": %s}}}, "l": {"L": [{"NS": %s}]}}
            """;
        ItemChange reordered = new ItemChange(
                MAPPER.readTree(item.formatted("[\"a\", \"b\"]", "[\"1\", \"2\"]", "[\"AQ==\", \"Ag==\"]",
                        "[\"x\", \"y\"]", "[\"10\", \"20\"]")),
                MAPPER.readTree(item.formatted("[\"b\", \"a\"]", "[\"2\", \"1\"]", "[\"Ag==\", \"AQ==\"]",
                        "[\"y\", \"x\"]", "[\"20\", \"10\"]")),
                Instant.EPOCH);

        assertEquals(List.of(), DynamoDbExportRecords.records(List.of(reordered), TABLE, false));
    }
}
