package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.floci.aws.testing.EnforcementFixtures.createUser;
import static io.floci.aws.testing.EnforcementFixtures.putUserPolicy;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class DynamoDbPartiQLAuthorizationIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureContentType() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Select", "Insert", "Update", "Delete"})
    void statementUsesItsPartiQLActionOnTheSpecificTable(String operation) {
        String table = table();
        String key = user("dynamodb:PartiQL" + operation, arn(table));
        String statement = switch (operation) {
            case "Select" -> "  select * FROM \"" + table + "\" WHERE pk = 'a'";
            case "Insert" -> "INSERT INTO \"" + table + "\" VALUE {'pk':'b'}";
            case "Update" -> "UPDATE \"" + table + "\" SET data = 'new' WHERE pk = 'a'";
            case "Delete" -> "DELETE FROM \"" + table + "\" WHERE pk = 'a'";
            default -> throw new IllegalArgumentException(operation);
        };
        execute(key, statement).statusCode(200);
        if (!"Select".equals(operation)) {
            execute(key, "SELECT * FROM \"" + table + "\"").statusCode(400)
                    .body(containsString("dynamodb:PartiQLSelect"));
        }
    }

    @Test
    void tableScopedSelectCannotReadAnotherTableOrWrite() {
        String table = table();
        String other = table();
        String key = user("dynamodb:PartiQLSelect", arn(table));
        execute(key, "SELECT * FROM \"" + table + "\" WHERE pk = 'a'")
                .statusCode(200).body("Items[0].pk.S", equalTo("a"));
        execute(key, "SELECT * FROM \"" + other + "\"").statusCode(400)
                .body(containsString("AccessDeniedException"));
        execute(key, "DELETE FROM \"" + table + "\" WHERE pk = 'a'").statusCode(400)
                .body(containsString("dynamodb:PartiQLDelete"));
        execute("test", "SELECT * FROM \"" + table + "\"")
                .statusCode(200).body("Items[0].pk.S", equalTo("a"));
    }

    @Test
    void wireOperationPermissionDoesNotGrantPartiQLAccess() {
        String table = table();
        String key = user("dynamodb:ExecuteStatement", arn(table));
        execute(key, "SELECT * FROM \"" + table + "\"").statusCode(400)
                .body(containsString("dynamodb:PartiQLSelect"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-- only a comment", "/* only a comment */", "SELECT * FROM t /* unterminated"})
    void statementWithNoOperationIsAuthorizedAsExecuteStatement(String statement) {
        String table = table();
        execute(user("dynamodb:PartiQLSelect", arn(table)), statement).statusCode(400)
                .body(containsString("AccessDeniedException"))
                .body(containsString("dynamodb:ExecuteStatement"));
        execute(user("dynamodb:ExecuteStatement", "*"), statement).statusCode(400)
                .body(containsString("ValidationException"));
    }

    @ParameterizedTest
    @CsvSource({"Select,target", "Insert,target", "Update,target", "Delete,target",
            "Select,amz-target", "Insert,amz-target", "Update,amz-target", "Delete,amz-target",
            "Select,route", "Insert,route", "Update,route", "Delete,route"})
    void binaryStatementsRequireTheirPartiQLGrant(String operation, String protocol) throws Exception {
        String table = table();
        String statement = switch (operation) {
            case "Select" -> "SELECT * FROM \"" + table + "\" WHERE pk = 'a'";
            case "Insert" -> "INSERT INTO \"" + table + "\" VALUE {'pk':'b'}";
            case "Update" -> "UPDATE \"" + table + "\" SET data = 'new' WHERE pk = 'a'";
            case "Delete" -> "DELETE FROM \"" + table + "\" WHERE pk = 'a'";
            default -> throw new IllegalArgumentException(operation);
        };
        JsonNode denied = binary(user("dynamodb:ExecuteStatement", arn(table)), statement, protocol, 403);
        assertTrue(denied.path("__type").asText().contains("AccessDeniedException"), denied.toString());
        assertTrue(denied.path("message").asText().contains("dynamodb:PartiQL" + operation), denied.toString());
        binary(user("dynamodb:PartiQL" + operation, arn(table)), statement, protocol, 200);
        binary(user("dynamodb:PartiQL" + operation, arn(table())), statement, protocol, 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"target", "amz-target", "route"})
    void binaryDeleteHonorsAnExplicitPartiQLDeny(String protocol) throws Exception {
        String table = table();
        String name = "partiql-" + UUID.randomUUID();
        UserCredentials credentials = createUser(cleanup, endpoint.getPort(), auth("test", "iam"), name);
        putUserPolicy(endpoint.getPort(), auth("test", "iam"), name, "deny-delete", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:*","Resource":"*"},
                  {"Effect":"Deny","Action":"dynamodb:PartiQLDelete","Resource":"%s"}
                ]}
                """.formatted(arn(table)));
        JsonNode denied = binary(credentials.accessKeyId(),
                "DELETE FROM \"" + table + "\" WHERE pk = 'a'", protocol, 403);
        assertTrue(denied.path("__type").asText().contains("AccessDeniedException"), denied.toString());
        execute("test", "SELECT * FROM \"" + table + "\"")
                .statusCode(200).body("Items[0].pk.S", equalTo("a"));
    }

    private JsonNode binary(String key, String statement, String protocol, int status) throws Exception {
        ObjectMapper cbor = new ObjectMapper(new CBORFactory());
        String path = "route".equals(protocol) ? "/service/DynamoDB_20120810/operation/ExecuteStatement" : "/";
        String contentType = "amz-target".equals(protocol) ? "application/x-amz-cbor-1.1" : "application/cbor";
        RequestSpecification request = given().port(endpoint.getPort())
                .header("Authorization", auth(key, "dynamodb"))
                // The route dispatches ExecuteStatement even if a target header names another operation.
                .header("X-Amz-Target", "DynamoDB_20120810." + ("route".equals(protocol) ? "ListTables" : "ExecuteStatement"))
                .contentType(contentType).accept(contentType)
                .body(cbor.writeValueAsBytes(Map.of("Statement", statement)));
        if ("route".equals(protocol)) {
            request.header("smithy-protocol", "rpc-v2-cbor");
        }
        byte[] response = request.post(path).then().statusCode(status).extract().asByteArray();
        JsonNode body = cbor.readTree(response);
        assertTrue(body.isObject());
        return body;
    }

    private String arn(String table) {
        return "arn:aws:dynamodb:us-east-1:000000000000:table/" + table;
    }

    private String user(String action, String resource) {
        String name = "partiql-" + UUID.randomUUID();
        UserCredentials credentials = createUser(cleanup, endpoint.getPort(), auth("test", "iam"), name);
        putUserPolicy(endpoint.getPort(), auth("test", "iam"), name, "statement", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"%s","Resource":"%s"}]}
                """.formatted(action, resource));
        return credentials.accessKeyId();
    }

    private String table() {
        String name = "partiql-" + UUID.randomUUID();
        cleanup.register(() -> json("test", "DeleteTable", Map.of("TableName", name)).statusCode(200));
        json("test", "CreateTable", Map.of("TableName", name,
                "AttributeDefinitions", new Object[]{Map.of("AttributeName", "pk", "AttributeType", "S")},
                "KeySchema", new Object[]{Map.of("AttributeName", "pk", "KeyType", "HASH")},
                "BillingMode", "PAY_PER_REQUEST")).statusCode(200);
        json("test", "PutItem", Map.of("TableName", name, "Item", Map.of("pk", Map.of("S", "a"))))
                .statusCode(200);
        return name;
    }

    private ValidatableResponse execute(String key, String statement) {
        return json(key, "ExecuteStatement", Map.of("Statement", statement));
    }

    private ValidatableResponse json(String key, String operation, Object body) {
        return given().port(endpoint.getPort()).header("Authorization", auth(key, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810." + operation)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then();
    }
}
