package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.DynamoDbRequests.ddb;

final class DynamoDbAttributeOrderFixture {

    static final String MAP = """
            {"M":{"k1":{"S":"1"},"k2":{"S":"2"},"k3":{"S":"3"},"k4":{"S":"4"},"k5":{"S":"5"},"k6":{"S":"6"}}}""";
    static final String ITEM = """
            {"pk":{"S":"k"},"a1":{"S":"1"},"a2":{"S":"2"},"a3":{"S":"3"},"a4":{"S":"4"},"a5":{"S":"5"},"m":%s}
            """.formatted(MAP);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DynamoDbAttributeOrderFixture() {
    }

    static String createTable(String prefix) {
        String table = prefix + "-" + UUID.randomUUID();
        ddb("CreateTable", """
                {"TableName":"%s","BillingMode":"PAY_PER_REQUEST",
                 "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}]}
                """.formatted(table)).statusCode(200);
        return table;
    }

    static void deleteTable(String table) {
        ddb("DeleteTable", "{\"TableName\":\"" + table + "\"}").statusCode(200);
    }

    static JsonNode call(String action, String body) {
        String response = ddb(action, body).extract().asString();
        try {
            return MAPPER.readTree(response);
        } catch (Exception e) {
            throw new IllegalStateException(response, e);
        }
    }

    static String order(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return String.join(",", names);
    }
}
