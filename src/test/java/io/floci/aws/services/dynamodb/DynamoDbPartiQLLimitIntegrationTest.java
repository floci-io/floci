package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.floci.aws.testing.DynamoDbRequests.ddb;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * ExecuteStatement reads a Limit written past the int range, with an exponent or with a fraction.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPartiQLLimitIntegrationTest {

    private static final String TABLE = "partiql-limit";
    private static final String PARTITION = "SELECT * FROM \"" + TABLE + "\" WHERE pk = 'p'";
    private static final String WHOLE_TABLE = "SELECT * FROM \"" + TABLE + "\"";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTable() {
        ddb("CreateTable", """
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [
                    {"AttributeName":"pk","AttributeType":"S"},
                    {"AttributeName":"sk","AttributeType":"S"}
                  ],
                  "KeySchema": [
                    {"AttributeName":"pk","KeyType":"HASH"},
                    {"AttributeName":"sk","KeyType":"RANGE"}
                  ],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
            .statusCode(200);
        for (int row = 1; row <= 4; row++) {
            ddb("PutItem", """
                    {"TableName":"%s","Item":{"pk":{"S":"p"},"sk":{"S":"row-%d"}}}
                    """.formatted(TABLE, row))
                .statusCode(200);
        }
    }

    @ParameterizedTest
    @Order(2)
    @ValueSource(strings = {
        "2147483648", "4294967297", "9223372036854775808", "18446744073709551617",
        "100000000000000000000", "1E+2", "1e+20", "1e400",
    })
    void aLimitPastTheIntRangeReadsTheWholePartition(String limit) {
        readsEveryRow(PARTITION, limit);
    }

    @ParameterizedTest
    @Order(3)
    @ValueSource(strings = {"2147483648", "4294967297", "9223372036854775808", "18446744073709551617", "1e+20"})
    void aLimitPastTheIntRangeReadsTheWholeTable(String limit) {
        readsEveryRow(WHOLE_TABLE, limit);
    }

    @ParameterizedTest
    @Order(4)
    @CsvSource({"2.9, 2", "1.5, 1", "1.5e0, 1", "10e-1, 1"})
    void aLimitWithAFractionReadsItsWholePart(String limit, int rows) {
        read(PARTITION, limit)
            .statusCode(200)
            .body("Items.size()", equalTo(rows))
            .body("NextToken", notNullValue());
    }

    @ParameterizedTest
    @Order(5)
    @CsvSource({
        "0.5, 0", "0.999, 0", "1e-400, 0", "-0.5, 0", "-1, -1", "-2147483649, -2147483648",
        "-18446744073709551615, -2147483648", "-1e+20, -2147483648",
    })
    void aLimitBelowOneIsReportedAsTheIntItReadsAs(String limit, String reported) {
        read(PARTITION, limit)
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value '" + reported + "' at 'limit' "
                    + "failed to satisfy constraint: Member must have value greater than or equal to 1"));
    }

    private static void readsEveryRow(String statement, String limit) {
        read(statement, limit)
            .statusCode(200)
            .body("Items.sk.S", contains("row-1", "row-2", "row-3", "row-4"))
            .body("NextToken", nullValue());
    }

    private static ValidatableResponse read(String statement, String limit) {
        return ddb("ExecuteStatement", "{\"Statement\":" + JsonNodeFactory.instance.textNode(statement)
                + ",\"Limit\":" + limit + "}");
    }
}
