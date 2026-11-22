package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.ITEM;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.call;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.createTable;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.deleteTable;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.order;
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

    private String table;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createTestTable() {
        table = createTable("attribute-order-off");
    }

    @AfterEach
    void deleteTestTable() {
        deleteTable(table);
    }

    @Test
    void storedOrderIsKeptWhenTheSettingIsOff() {
        String put = "{\"TableName\":\"" + table + "\",\"Item\":" + ITEM + ",\"ReturnValues\":\"ALL_OLD\"}";
        call("PutItem", put);
        Set<String> topOrders = new HashSet<>();
        Set<String> mapOrders = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            JsonNode attributes = call("PutItem", put).get("Attributes");
            topOrders.add(order(attributes));
            mapOrders.add(order(attributes.get("m").get("M")));
            JsonNode transacted = call("TransactGetItems", "{\"TransactItems\":[{\"Get\":{\"TableName\":\""
                    + table + "\",\"Key\":{\"pk\":{\"S\":\"k\"}}}}]}").get("Responses").get(0).get("Item");
            topOrders.add(order(transacted));
            mapOrders.add(order(transacted.get("m").get("M")));
        }
        assertEquals(1, topOrders.size(), topOrders.toString());
        assertEquals(1, mapOrders.size(), mapOrders.toString());
        assertEquals("k1,k2,k3,k4,k5,k6", mapOrders.iterator().next());
    }
}
