package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DynamoDbSetOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void stringsOrderByCodePoint() {
        List<String> sorted = sorted("SS",
                List.of("b", "a", "", "B", "aa", "é", "Z", "~", "ab", "～", "😀"));
        assertEquals(List.of("", "B", "Z", "a", "aa", "ab", "b", "~", "é", "～", "😀"), sorted);
    }

    @Test
    void numbersOrderByValue() {
        List<String> sorted = sorted("NS", List.of("10", "9", "-1", "1.5", "0", "1e2", "0.001", "-0.5"));
        assertEquals(List.of("-1", "-0.5", "0", "0.001", "1.5", "9", "10", "1e2"), sorted);
    }

    @Test
    void binariesOrderByUnsignedByte() {
        List<String> sorted = sorted("BS", List.of(base64(0x80), base64(0x02), base64(0xff), base64(0x01, 0x00),
                base64(0x00), base64(0xc0), base64(0x7f), base64(0x01), base64(0x00, 0xff), base64(0x01, 0x01)));
        assertEquals(List.of(base64(0x00), base64(0x00, 0xff), base64(0x01), base64(0x01, 0x00), base64(0x01, 0x01),
                base64(0x02), base64(0x7f), base64(0x80), base64(0xc0), base64(0xff)), sorted);
    }

    @Test
    void sortSetsInReachesMapsAndLists() throws Exception {
        ObjectNode item = (ObjectNode) MAPPER.readTree("""
                {"pk":{"S":"a"},"ss":{"SS":["b","a"]},
                 "m":{"M":{"deep":{"SS":["b","a",""]},"nums":{"NS":["10","9"]}}},
                 "l":{"L":[{"NS":["2","1"]},{"M":{"inner":{"BS":["gA==","AQ=="]}}}]}}
                """);
        DynamoDbSetOrder.sortSetsIn(item);
        assertEquals(MAPPER.readTree("""
                {"pk":{"S":"a"},"ss":{"SS":["a","b"]},
                 "m":{"M":{"deep":{"SS":["","a","b"]},"nums":{"NS":["9","10"]}}},
                 "l":{"L":[{"NS":["1","2"]},{"M":{"inner":{"BS":["AQ==","gA=="]}}}]}}
                """), item);
    }

    @Test
    void membersThatDoNotParseKeepAPlace() {
        assertEquals(List.of("abc", "0", "1"), sorted("NS", List.of("1", "abc", "0")));
        assertEquals(List.of("AQ==", "not base64"), sorted("BS", List.of("not base64", "AQ==")));
    }

    @Test
    void aMemberListThatIsNotAnArrayIsLeftAlone() throws Exception {
        JsonNode members = MAPPER.readTree("\"x\"");
        assertSame(members, DynamoDbSetOrder.sorted("SS", members));
    }

    private static List<String> sorted(String setType, List<String> members) {
        List<String> result = new ArrayList<>();
        DynamoDbSetOrder.sorted(setType, MAPPER.valueToTree(members)).forEach(node -> result.add(node.asText()));
        return result;
    }

    private static String base64(int... bytes) {
        byte[] raw = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            raw[i] = (byte) bytes[i];
        }
        return Base64.getEncoder().encodeToString(raw);
    }
}
