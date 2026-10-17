package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.createUser;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.removeRole;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * The boundaries of IAM authorization for DynamoDB transaction items: the caller's account and
 * region, foreign ARNs, session policies, service control policies and request shape checks.
 *
 * <p>{@code scpDeniesUnderlyingItemActionForMemberRootAndUser} relies on the SCP enforcement that
 * {@link IamEnforcementProfile} turns on next to IAM enforcement, and removes the organization it
 * creates once it ends.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class DynamoDbTransactionPolicyBoundaryIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @Inject
    ObjectMapper mapper;

    @BeforeAll
    static void configureContentType() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,false", "us-east-1,true", "cn-north-1,false", "cn-north-1,true"})
    void itemResourcesAndGlobalContextUseCallerAccountAndRegion(String region, boolean useArn) {
        String name = "tx-boundary-" + UUID.randomUUID();
        Table first = table("717171717171", region, name);
        Table otherAccount = table("727272727272", region, name);
        Table otherRegion = table(first.account(), "us-west-2", name);
        String conditions = ",\"Condition\":{\"StringEquals\":{\"aws:PrincipalAccount\":\""
                + first.account() + "\",\"aws:ResourceAccount\":\"" + first.account()
                + "\",\"aws:RequestedRegion\":\"" + region + "\"}}";
        String caller = user(first.account(), policy("dynamodb:PutItem", first.arn(), conditions));
        transact(caller, first.region(), put(first, "item", useArn)).statusCode(200);
        read(first, "item").body("Item.value.S", equalTo("changed"));
        read(otherAccount, "item").body("Item.value.S", equalTo("original"));
        read(otherRegion, "item").body("Item.value.S", equalTo("original"));
        transact(caller, otherRegion.region(), put(otherRegion, "item", useArn)).statusCode(400)
                .body(containsString("AccessDeniedException"));
        String otherCaller = user(otherAccount.account(), policy("dynamodb:PutItem", first.arn(), conditions));
        transact(otherCaller, otherAccount.region(), put(otherAccount, "item", useArn)).statusCode(400)
                .body(containsString("AccessDeniedException"));
        read(otherAccount, "item").body("Item.value.S", equalTo("original"));
        read(otherRegion, "item").body("Item.value.S", equalTo("original"));
    }

    @ParameterizedTest
    @CsvSource({"Put,us-east-1", "Update,us-east-1", "Delete,us-east-1", "ConditionCheck,us-east-1",
            "Get,us-east-1", "Put,cn-north-1", "Update,cn-north-1", "Delete,cn-north-1",
            "ConditionCheck,cn-north-1", "Get,cn-north-1"})
    void foreignArnPermissionCannotAuthorizeTheLocalExecutionTarget(String operation, String region) {
        String name = "tx-foreign-" + UUID.randomUUID();
        Table local = table("757575757575", region, name);
        Table foreign = table("767676767676", region, name);
        Table earlier = table(local.account(), region, name + "-earlier");
        String document = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"dynamodb:*",
                 "Resource":["%s","%s"]}]}
                """.formatted(earlier.arn(), foreign.arn());
        String caller = user(local.account(), document);
        Map<String, Object> key = Map.of("PK", Map.of("S", "item"));
        Map<String, Object> request = switch (operation) {
            case "Put" -> Map.of("TableName", foreign.arn(), "Item", item("item", "changed"));
            case "Update" -> Map.of("TableName", foreign.arn(), "Key", key,
                    "UpdateExpression", "SET #v = :v", "ExpressionAttributeNames", Map.of("#v", "value"),
                    "ExpressionAttributeValues", Map.of(":v", Map.of("S", "changed")));
            case "ConditionCheck" -> Map.of("TableName", foreign.arn(), "Key", key,
                    "ConditionExpression", "attribute_exists(PK)");
            default -> Map.of("TableName", foreign.arn(), "Key", key);
        };
        ValidatableResponse response = operation.equals("Get")
                ? json(caller, region, "dynamodb", "DynamoDB_20120810.TransactGetItems",
                        encode(Map.of("TransactItems", List.of(Map.of(operation, request)))))
                : transact(caller, region, put(earlier, "item", true), Map.of(operation, request));
        assertAll(
                () -> response.statusCode(400).body(containsString("AccessDeniedException")),
                () -> read(earlier, "item").body("Item.value.S", equalTo("original")),
                () -> read(local, "item").body("Item.value.S", equalTo("original")),
                () -> read(foreign, "item").body("Item.value.S", equalTo("original")));
    }

    @Test
    void sessionPolicyRestrictsEachItemAndPreservesRolePrincipalContext() {
        Table table = table("737373737373", "us-east-1", "tx-session-" + UUID.randomUUID());
        String role = "tx-role-" + UUID.randomUUID();
        int port = endpoint.getPort();
        cleanup.register(() -> removeRole(port, auth(table.account(), "iam"), role));
        String roleArn = given().port(port).header("Authorization", auth(table.account(), "iam"))
                .formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                         "Principal":{"AWS":"*"},"Action":"sts:AssumeRole"}]}
                        """).post("/").then().statusCode(200).extract()
                .path("CreateRoleResponse.CreateRoleResult.Role.Arn");
        given().port(port).header("Authorization", auth(table.account(), "iam"))
                .formParam("Action", "PutRolePolicy").formParam("RoleName", role)
                .formParam("PolicyName", "transaction")
                .formParam("PolicyDocument", policy("dynamodb:PutItem", table.arn(),
                        ",\"Condition\":{\"ArnEquals\":{\"aws:PrincipalArn\":\"" + roleArn + "\"}}"))
                .post("/").then().statusCode(200);
        String sessionPolicy = """
                {"Version":"2012-10-17","Statement":[
                 {"Effect":"Allow","Action":"dynamodb:PutItem","Resource":"%s"},
                 {"Effect":"Deny","Action":"dynamodb:PutItem","Resource":"%s",
                  "Condition":{"ForAnyValue:StringEquals":{"dynamodb:LeadingKeys":["forbidden"]}}}]}
                """.formatted(table.arn(), table.arn());
        String session = given().port(port).header("Authorization", auth(table.account(), "sts"))
                .formParam("Action", "AssumeRole").formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", "transaction").formParam("Policy", sessionPolicy)
                .post("/").then().statusCode(200).extract()
                .path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
        transact(session, table.region(), put(table, "item", false), put(table, "forbidden", true))
                .statusCode(400).body(containsString("AccessDeniedException"));
        read(table, "item").body("Item.value.S", equalTo("original"));
        read(table, "forbidden").body(equalTo("{}"));
        transact(session, table.region(), put(table, "item", true)).statusCode(200);
        read(table, "item").body("Item.value.S", equalTo("changed"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scpDeniesUnderlyingItemActionForMemberRootAndUser(boolean rootCaller) {
        String management = Long.toString(ThreadLocalRandom.current().nextLong(800000000000L, 899999999999L));
        org(management, "CreateOrganization", Map.of("FeatureSet", "ALL")).statusCode(200);
        cleanup.register(() -> org(management, "DeleteOrganization", Map.of()).statusCode(200));
        String root = org(management, "ListRoots", Map.of()).statusCode(200).extract().path("Roots[0].Id");
        org(management, "EnablePolicyType", Map.of("RootId", root, "PolicyType", "SERVICE_CONTROL_POLICY"))
                .statusCode(200);
        String member = org(management, "CreateAccount", Map.of("AccountName", "tx-member",
                "Email", UUID.randomUUID() + "@example.com")).statusCode(200).extract()
                .path("CreateAccountStatus.AccountId");
        cleanup.register(() -> org(management, "RemoveAccountFromOrganization", Map.of("AccountId", member))
                .statusCode(200));
        Table table = table(member, "us-east-1", "tx-scp-" + UUID.randomUUID());
        String caller = rootCaller ? member : user(member, policy("dynamodb:*", table.arn(), ""));
        String content = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny",
                 "Action":"dynamodb:PutItem","Resource":"%s"}]}
                """.formatted(table.arn());
        String policyId = org(management, "CreatePolicy", Map.of("Name", "deny-item",
                "Type", "SERVICE_CONTROL_POLICY", "Description", "Deny transaction writes", "Content", content))
                .statusCode(200).extract().path("Policy.PolicySummary.Id");
        cleanup.register(() -> org(management, "DeletePolicy", Map.of("PolicyId", policyId)).statusCode(200));
        org(management, "AttachPolicy", Map.of("PolicyId", policyId, "TargetId", member)).statusCode(200);
        cleanup.register(() -> org(management, "DetachPolicy", Map.of("PolicyId", policyId, "TargetId", member))
                .statusCode(200));
        transact(caller, table.region(), put(table, "item", true)).statusCode(400)
                .body(containsString("AccessDeniedException"));
        read(table, "item").body("Item.value.S", equalTo("original"));
        org(management, "DetachPolicy", Map.of("PolicyId", policyId, "TargetId", member)).statusCode(200);
        transact(caller, table.region(), put(table, "item", true)).statusCode(200);
        read(table, "item").body("Item.value.S", equalTo("changed"));
        org(management, "AttachPolicy", Map.of("PolicyId", policyId, "TargetId", member)).statusCode(200);
    }

    @ParameterizedTest
    @CsvSource(value = {"{}|ValidationException", "{\"TransactItems\":[]}|ValidationException",
            "{\"TransactItems\":null}|ValidationException", "{\"TransactItems\":{}}|SerializationException"},
            delimiter = '|')
    void absentRecognizedItemsStillReachRequestShapeChecks(String body, String error) {
        Table table = table("747474747474", "us-east-1", "tx-shape-" + UUID.randomUUID());
        String caller = user(table.account(), policy("dynamodb:TransactWriteItems", "*", ""));
        json(caller, table.region(), "dynamodb", "DynamoDB_20120810.TransactWriteItems", body)
                .statusCode(400).body(containsString(error));
        read(table, "item").body("Item.value.S", equalTo("original"));
    }

    private Table table(String account, String region, String name) {
        String arn = json(account, region, "dynamodb", "DynamoDB_20120810.CreateTable", encode(Map.of(
                "TableName", name, "BillingMode", "PAY_PER_REQUEST",
                "KeySchema", List.of(Map.of("AttributeName", "PK", "KeyType", "HASH")),
                "AttributeDefinitions", List.of(Map.of("AttributeName", "PK", "AttributeType", "S")))))
                .statusCode(200).extract().path("TableDescription.TableArn");
        Table table = new Table(account, region, name, arn);
        cleanup.register(() -> json(account, region, "dynamodb", "DynamoDB_20120810.DeleteTable",
                encode(Map.of("TableName", name))).statusCode(200));
        json(account, region, "dynamodb", "DynamoDB_20120810.PutItem",
                encode(Map.of("TableName", name, "Item", item("item", "original")))).statusCode(200);
        return table;
    }

    private String user(String account, String policy) {
        String name = "tx-boundary-" + UUID.randomUUID();
        String key = createUser(cleanup, endpoint.getPort(), auth(account, "iam"), name).accessKeyId();
        given().port(endpoint.getPort()).header("Authorization", auth(account, "iam"))
                .formParam("Action", "PutUserPolicy").formParam("UserName", name)
                .formParam("PolicyName", "transaction").formParam("PolicyDocument", policy)
                .post("/").then().statusCode(200);
        return key;
    }

    @SafeVarargs
    private final ValidatableResponse transact(String key, String region, Map<String, Object>... items) {
        return json(key, region, "dynamodb", "DynamoDB_20120810.TransactWriteItems",
                encode(Map.of("TransactItems", List.of(items))));
    }

    private ValidatableResponse read(Table table, String key) {
        return json(table.account(), table.region(), "dynamodb", "DynamoDB_20120810.GetItem",
                encode(Map.of("TableName", table.name(), "Key", Map.of("PK", Map.of("S", key)))))
                .statusCode(200);
    }

    private static Map<String, Object> put(Table table, String key, boolean useArn) {
        return Map.of("Put", Map.of("TableName", useArn ? table.arn() : table.name(),
                "Item", item(key, "changed")));
    }

    private static Map<String, Object> item(String key, String value) {
        return Map.of("PK", Map.of("S", key), "value", Map.of("S", value));
    }

    private static String policy(String action, String resource, String condition) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + action + "\",\"Resource\":\"" + resource + "\"" + condition + "}]}";
    }

    private ValidatableResponse org(String account, String action, Map<String, String> body) {
        return json(account, "us-east-1", "organizations", "AWSOrganizationsV20161128." + action, encode(body));
    }

    private ValidatableResponse json(String key, String region, String service, String target, String body) {
        return given().port(endpoint.getPort())
                .header("Authorization", auth(key, service).replace("/us-east-1/", "/" + region + "/"))
                .header("X-Amz-Target", target)
                .contentType("dynamodb".equals(service) ? "application/x-amz-json-1.0" : "application/x-amz-json-1.1")
                .body(body).post("/").then();
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot encode transaction fixture", exception);
        }
    }

    private record Table(String account, String region, String name, String arn) {
    }
}
