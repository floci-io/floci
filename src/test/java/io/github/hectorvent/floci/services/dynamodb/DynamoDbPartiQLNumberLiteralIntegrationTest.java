package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.github.hectorvent.floci.testing.DynamoDbRequests.ddb;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * PartiQL number literals as DynamoDB reads them: a point may lead, an exponent may follow,
 * and an exponent outside a 32-bit integer makes the statement ill formed.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPartiQLNumberLiteralIntegrationTest {

    private static final String TABLE = "partiql-number-literals";
    private static final String NOT_WELL_FORMED = "Statement wasn't well formed, can't be processed: ";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void aLiteralWithNoLeadingDigitMatchesTheNumberItDenotes() {
        ddb("CreateTable", """
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [{"AttributeName":"pk","AttributeType":"N"}],
                  "KeySchema": [{"AttributeName":"pk","KeyType":"HASH"}],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
            .statusCode(200);
        for (String pk : new String[] {"7.3", "-7.3", "7310000"}) {
            ddb("PutItem", """
                    {"TableName":"%s","Item":{"pk":{"N":"%s"}}}
                    """.formatted(TABLE, pk))
                .statusCode(200);
        }

        select(".73e1").body("Items.pk.N", contains("7.3"));
        select("-.73e1").body("Items.pk.N", contains("-7.3"));
        select("+.73e1").body("Items.pk.N", contains("7.3"));
        select("731e4").body("Items.pk.N", contains("7310000"));
        select("73100000e-1").body("Items.pk.N", contains("7310000"));
    }

    @Test
    @Order(2)
    void anInsertStoresTheNumberALiteralDenotes() {
        statement("INSERT INTO \"%s\" VALUE {'pk': .7325e1, 'tag': 'x'}".formatted(TABLE)).statusCode(200);
        statement("INSERT INTO \"%s\" VALUE {'pk': -.5, 'tag': 'x'}".formatted(TABLE)).statusCode(200);

        getItem("7.325").body("Item.tag.S", equalTo("x"));
        getItem("-0.5").body("Item.tag.S", equalTo("x"));
    }

    @Test
    @Order(3)
    void anExponentOutsideAnIntIsNotWellFormed() {
        for (String literal : new String[] {"1e2147483648", "1e-2147483648", "1.5e-2147483647", "1e99999999999"}) {
            select(literal)
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(NOT_WELL_FORMED));
        }
        statement("INSERT INTO \"%s\" VALUE {'pk': 1e2147483648}".formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo(NOT_WELL_FORMED));
    }

    private static ValidatableResponse select(String literal) {
        return statement("SELECT * FROM \"%s\" WHERE pk = %s".formatted(TABLE, literal));
    }

    private static ValidatableResponse statement(String partiql) {
        String quoted = JsonNodeFactory.instance.textNode(partiql).toString();
        return ddb("ExecuteStatement", "{\"Statement\":" + quoted + ",\"ConsistentRead\":true}");
    }

    private static ValidatableResponse getItem(String pk) {
        return ddb("GetItem", """
                {"TableName":"%s","Key":{"pk":{"N":"%s"}},"ConsistentRead":true}
                """.formatted(TABLE, pk))
            .statusCode(200);
    }
}
