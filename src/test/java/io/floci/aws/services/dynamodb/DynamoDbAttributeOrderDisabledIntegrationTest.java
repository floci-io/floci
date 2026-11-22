package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(DynamoDbAttributeOrderDisabledIntegrationTest.DisabledProfile.class)
class DynamoDbAttributeOrderDisabledIntegrationTest {

    public static final class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.dynamodb.randomize-attribute-order", "false");
        }
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TABLE = "AttributeOrderOffTable";
    private static final String ITEM = """
            {"pk":{"S":"k"},"a1":{"S":"1"},"a2":{"S":"2"},"a3":{"S":"3"},"a4":{"S":"4"},
             "m":{"M":{"k1":{"S":"1"},"k2":{"S":"2"},"k3":{"S":"3"},"k4":{"S":"4"},"k5":{"S":"5"},"k6":{"S":"6"}}}}
            """;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static JsonNode call(String action, String body) throws Exception {
        return MAPPER.readTree(given()
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when().post("/")
                .then().extract().asString());
    }

    private static String order(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return String.join(",", names);
    }

    @Test
    void storedOrderIsKeptWhenTheSettingIsOff() throws Exception {
        call("CreateTable", "{\"TableName\":\"" + TABLE + "\",\"BillingMode\":\"PAY_PER_REQUEST\","
                + "\"KeySchema\":[{\"AttributeName\":\"pk\",\"KeyType\":\"HASH\"}],"
                + "\"AttributeDefinitions\":[{\"AttributeName\":\"pk\",\"AttributeType\":\"S\"}]}");
        String put = "{\"TableName\":\"" + TABLE + "\",\"Item\":" + ITEM + ",\"ReturnValues\":\"ALL_OLD\"}";
        call("PutItem", put);
        Set<String> topOrders = new HashSet<>();
        Set<String> mapOrders = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            JsonNode attributes = call("PutItem", put).get("Attributes");
            topOrders.add(order(attributes));
            mapOrders.add(order(attributes.get("m").get("M")));
            JsonNode transacted = call("TransactGetItems", "{\"TransactItems\":[{\"Get\":{\"TableName\":\""
                    + TABLE + "\",\"Key\":{\"pk\":{\"S\":\"k\"}}}}]}").get("Responses").get(0).get("Item");
            topOrders.add(order(transacted));
            mapOrders.add(order(transacted.get("m").get("M")));
        }
        assertEquals(1, topOrders.size(), topOrders.toString());
        assertEquals(1, mapOrders.size(), mapOrders.toString());
        assertEquals("k1,k2,k3,k4,k5,k6", mapOrders.iterator().next());
    }
}
