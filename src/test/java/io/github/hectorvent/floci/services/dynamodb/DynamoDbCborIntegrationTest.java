package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests the smithy-rpc-v2-cbor protocol for DynamoDB.
 * AWS SDK v2 sends DynamoDB requests as CBOR to /service/DynamoDB/operation/{op}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbCborIntegrationTest {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Test
    @Order(1)
    void createTableViaCbor() throws Exception {
        JsonNode request = JSON_MAPPER.readTree("""
            {
                "TableName": "CborTable",
                "KeySchema": [
                    {"AttributeName": "pk", "KeyType": "HASH"}
                ],
                "AttributeDefinitions": [
                    {"AttributeName": "pk", "AttributeType": "S"}
                ],
                "ProvisionedThroughput": {
                    "ReadCapacityUnits": 5,
                    "WriteCapacityUnits": 5
                }
            }
            """);

        byte[] cborBody = CBOR_MAPPER.writeValueAsBytes(request);

        byte[] responseBytes = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .body(cborBody)
        .when()
            .post("/service/DynamoDB/operation/CreateTable")
        .then()
            .statusCode(200)
            .contentType("application/cbor")
            .extract().asByteArray();

        JsonNode response = CBOR_MAPPER.readTree(responseBytes);
        assertThat(response.path("TableDescription").path("TableName").asText(), equalTo("CborTable"));
        assertThat(response.path("TableDescription").path("TableStatus").asText(), equalTo("ACTIVE"));
    }

    @Test
    @Order(2)
    void describeTableViaCbor() throws Exception {
        JsonNode request = JSON_MAPPER.readTree("""
            {"TableName": "CborTable"}
            """);

        byte[] cborBody = CBOR_MAPPER.writeValueAsBytes(request);

        byte[] responseBytes = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .body(cborBody)
        .when()
            .post("/service/DynamoDB/operation/DescribeTable")
        .then()
            .statusCode(200)
            .contentType("application/cbor")
            .extract().asByteArray();

        JsonNode response = CBOR_MAPPER.readTree(responseBytes);
        assertThat(response.path("Table").path("TableName").asText(), equalTo("CborTable"));
        assertThat(response.path("Table").path("TableStatus").asText(), equalTo("ACTIVE"));
    }

    @Test
    @Order(3)
    void describeNonExistentTableViaCbor() throws Exception {
        JsonNode request = JSON_MAPPER.readTree("""
            {"TableName": "NoSuchTable"}
            """);

        byte[] cborBody = CBOR_MAPPER.writeValueAsBytes(request);

        byte[] responseBytes = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .body(cborBody)
        .when()
            .post("/service/DynamoDB/operation/DescribeTable")
        .then()
            .statusCode(400)
            .extract().asByteArray();

        JsonNode response = CBOR_MAPPER.readTree(responseBytes);
        assertThat(response.path("__type").asText(), equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(4)
    void putAndGetItemViaCbor() throws Exception {
        JsonNode putRequest = JSON_MAPPER.readTree("""
            {
                "TableName": "CborTable",
                "Item": {
                    "pk": {"S": "cbor-1"},
                    "data": {"S": "hello cbor"}
                }
            }
            """);

        given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(putRequest))
        .when()
            .post("/service/DynamoDB/operation/PutItem")
        .then()
            .statusCode(200);

        JsonNode getRequest = JSON_MAPPER.readTree("""
            {
                "TableName": "CborTable",
                "Key": {"pk": {"S": "cbor-1"}}
            }
            """);

        byte[] responseBytes = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(getRequest))
        .when()
            .post("/service/DynamoDB/operation/GetItem")
        .then()
            .statusCode(200)
            .extract().asByteArray();

        JsonNode response = CBOR_MAPPER.readTree(responseBytes);
        assertThat(response.path("Item").path("data").path("S").asText(), equalTo("hello cbor"));
    }

    @Test
    @Order(5)
    void deleteTableViaCbor() throws Exception {
        JsonNode request = JSON_MAPPER.readTree("""
            {"TableName": "CborTable"}
            """);

        byte[] responseBytes = given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(request))
        .when()
            .post("/service/DynamoDB/operation/DeleteTable")
        .then()
            .statusCode(200)
            .extract().asByteArray();

        JsonNode response = CBOR_MAPPER.readTree(responseBytes);
        assertThat(response.path("TableDescription").path("TableStatus").asText(), equalTo("DELETING"));
    }
}
