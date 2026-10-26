package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for the SES v2 reputation entity operations. Every tenant is an entity
 * referenced by its full ARN; shapes, messages and precedence follow the 2026-10-23 probes.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesReputationEntityV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String PREFIX = "arn:aws:ses:us-east-1:000000000000:tenant/floci-rep-it-";
    private static final String POLICY_PREFIX = "arn:aws:ses:us-east-1:aws:reputation-policy/";
    private static final String INVALID_IDENTIFIER = "Provided resource identifier is invalid";

    private static String arnA;
    private static String arnB;

    // The reference is sent percent-encoded as the SDKs send it, so RestAssured must not encode it again.
    private static RequestSpecification v2() {
        return given().urlEncodingEnabled(false).contentType("application/json").header("Authorization", AUTH);
    }

    private static String entityPath(String type, String reference) {
        return "/v2/email/reputation/entities/" + type + "/" + URLEncoder.encode(reference, StandardCharsets.UTF_8);
    }

    private static String createTenant(String name) {
        return v2().body("{\"TenantName\":\"" + name + "\"}")
                .when().post("/v2/email/tenants").then().statusCode(200)
                .extract().path("TenantArn");
    }

    private static String prefixFilter(String extra) {
        return "{\"Filter\":{\"ENTITY_REFERENCE_PREFIX\":\"" + PREFIX + "\"}" + extra + "}";
    }

    @Test
    @Order(1)
    void setup_tenants() {
        arnA = createTenant("floci-rep-it-a");
        arnB = createTenant("floci-rep-it-b");
    }

    @Test
    @Order(2)
    void list_everyTenantIsAnEntityWithEveryMemberRendered() {
        v2().body(prefixFilter(""))
                .when().post("/v2/email/reputation/entities").then().statusCode(200)
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue())
                .body("ReputationEntities.ReputationEntityReference", contains(arnA, arnB))
                .body("ReputationEntities[0]", hasKey("AwsSesManagedStatus"))
                .body("ReputationEntities[0].AwsSesManagedStatus", nullValue())
                .body("ReputationEntities[0]", hasKey("CustomerManagedStatus"))
                .body("ReputationEntities[0].CustomerManagedStatus", nullValue())
                .body("ReputationEntities[0].ReputationEntityType", equalTo("RESOURCE"))
                .body("ReputationEntities[0].ReputationImpact", equalTo("NONE"))
                .body("ReputationEntities[0].ReputationManagementPolicy", equalTo(POLICY_PREFIX + "standard"))
                .body("ReputationEntities[0].SendingStatusAggregate", equalTo("ENABLED"));
    }

    @Test
    @Order(3)
    void get_matchesOnlyTheFullArn() {
        v2().when().get(entityPath("RESOURCE", arnA)).then().statusCode(200)
                .body("ReputationEntity.ReputationEntityReference", equalTo(arnA))
                .body("ReputationEntity.SendingStatusAggregate", equalTo("ENABLED"));

        String tenantId = arnA.substring(arnA.lastIndexOf('/') + 1);
        for (String other : new String[] {
                "arn:aws:ses:us-east-1:000000000000:tenant/" + tenantId,
                PREFIX + "a",
                PREFIX + "wrong/" + tenantId,
                arnA.replace("us-east-1", "us-west-2"),
                "arn:aws:ses:us-east-1:000000000000:identity/example.com"}) {
            v2().when().get(entityPath("RESOURCE", other)).then().statusCode(400)
                    .body("__type", equalTo("BadRequestException"))
                    .body("message", equalTo("ReputationEntity <" + other + "> does not exist"));
        }
    }

    @Test
    @Order(4)
    void literalSlashesInTheReference_reachNoRoute() {
        v2().when().get("/v2/email/reputation/entities/RESOURCE/" + arnA).then().statusCode(404);
        v2().body("{\"SendingStatus\":\"DISABLED\"}")
                .when().put("/v2/email/reputation/entities/RESOURCE/" + arnA + "/customer-managed-status")
                .then().statusCode(404);
        v2().body("{\"TenantName\":\"floci-rep-it-a\"}")
                .when().post("/v2/email/tenants/get").then().statusCode(200)
                .body("Tenant.SendingStatus", equalTo("ENABLED"));
    }

    @Test
    @Order(5)
    void get_typeThenReferenceFormat() {
        v2().when().get(entityPath("FOO", "hello")).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at 'reputationEntityType' failed "
                        + "to satisfy constraint: Member must satisfy enum value set: [RESOURCE]"));
        v2().when().get(entityPath("RESOURCE", "hello")).then().statusCode(400)
                .body("message", equalTo(INVALID_IDENTIFIER));
        v2().when().get(entityPath("RESOURCE", "arn:aws:sqs:us-east-1:000000000000:q")).then().statusCode(400)
                .body("message", equalTo(INVALID_IDENTIFIER));
    }

    @Test
    @Order(6)
    void updatePolicy_storesTheGivenArn() {
        v2().body("{\"ReputationEntityPolicy\":\"" + POLICY_PREFIX + "strict\"}")
                .when().put(entityPath("RESOURCE", arnA) + "/policy").then().statusCode(200);
        v2().when().get(entityPath("RESOURCE", arnA)).then().statusCode(200)
                .body("ReputationEntity.ReputationManagementPolicy", equalTo(POLICY_PREFIX + "strict"));

        String otherRegion = "arn:aws:ses:us-west-2:aws:reputation-policy/none";
        v2().body("{\"ReputationEntityPolicy\":\"" + otherRegion + "\"}")
                .when().put(entityPath("RESOURCE", arnA) + "/policy").then().statusCode(200);
        v2().when().get(entityPath("RESOURCE", arnA)).then().statusCode(200)
                .body("ReputationEntity.ReputationManagementPolicy", equalTo(otherRegion));
    }

    @Test
    @Order(7)
    void updatePolicy_checksThePolicyFirst() {
        for (String body : new String[] {
                "{}",
                "{\"ReputationEntityPolicy\":\"standard\"}",
                "{\"ReputationEntityPolicy\":\"" + POLICY_PREFIX + "lax\"}",
                "{\"ReputationEntityPolicy\":\"" + POLICY_PREFIX + "STANDARD\"}",
                "{\"ReputationEntityPolicy\":\"arn:aws:ses:us-east-1:000000000000:reputation-policy/standard\"}"}) {
            v2().body(body).when().put(entityPath("RESOURCE", arnA) + "/policy").then().statusCode(400)
                    .body("__type", equalTo("BadRequestException"))
                    .body("message", equalTo(INVALID_IDENTIFIER));
        }
        // Even a bad entity type loses to a bad policy.
        v2().body("{\"ReputationEntityPolicy\":\"x\"}")
                .when().put(entityPath("FOO", PREFIX + "ghost/tn-0") + "/policy").then().statusCode(400)
                .body("message", equalTo(INVALID_IDENTIFIER));
        v2().body("{\"ReputationEntityPolicy\":\"" + POLICY_PREFIX + "standard\"}")
                .when().put(entityPath("RESOURCE", PREFIX + "ghost/tn-0") + "/policy").then().statusCode(400)
                .body("message", equalTo("ReputationEntity <" + PREFIX + "ghost/tn-0> does not exist"));
    }

    @Test
    @Order(8)
    void updateStatus_disabledIsTheAggregateEverywhere() {
        v2().body("{\"SendingStatus\":\"DISABLED\"}")
                .when().put(entityPath("RESOURCE", arnA) + "/customer-managed-status").then().statusCode(200);

        v2().when().get(entityPath("RESOURCE", arnA)).then().statusCode(200)
                .body("ReputationEntity.CustomerManagedStatus.Status", equalTo("DISABLED"))
                .body("ReputationEntity.CustomerManagedStatus.Cause", equalTo("Status manually updated."))
                .body("ReputationEntity.CustomerManagedStatus.LastUpdatedTimestamp", notNullValue())
                .body("ReputationEntity.AwsSesManagedStatus", nullValue())
                .body("ReputationEntity.SendingStatusAggregate", equalTo("DISABLED"));
        v2().body("{\"TenantName\":\"floci-rep-it-a\"}")
                .when().post("/v2/email/tenants/get").then().statusCode(200)
                .body("Tenant.SendingStatus", equalTo("DISABLED"));
        v2().body("{\"Filter\":{\"SENDING_STATUS\":\"DISABLED\",\"TENANT_NAME_CONTAINS\":\"floci-rep-it-\"}}")
                .when().post("/v2/email/tenants/list").then().statusCode(200)
                .body("Tenants.TenantArn", contains(arnA));
        // The entity filter takes one key, so other classes' tenants may show up here as well.
        v2().body("{\"Filter\":{\"SENDING_STATUS\":\"DISABLED\"}}")
                .when().post("/v2/email/reputation/entities").then().statusCode(200)
                .body("ReputationEntities.ReputationEntityReference", hasItem(arnA))
                .body("ReputationEntities.ReputationEntityReference", not(hasItem(arnB)));

        // The same status again is accepted, not a conflict.
        v2().body("{\"SendingStatus\":\"DISABLED\"}")
                .when().put(entityPath("RESOURCE", arnA) + "/customer-managed-status").then().statusCode(200);
    }

    @Test
    @Order(9)
    void updateStatus_referenceThenStatusThenExistence() {
        v2().body("{}")
                .when().put(entityPath("RESOURCE", "hello") + "/customer-managed-status").then().statusCode(400)
                .body("message", equalTo(INVALID_IDENTIFIER));
        v2().body("{}")
                .when().put(entityPath("RESOURCE", arnA) + "/customer-managed-status").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'sendingStatus' failed to "
                        + "satisfy constraint: Member must not be null"));
        v2().body("{\"SendingStatus\":\"PAUSED\"}")
                .when().put(entityPath("RESOURCE", PREFIX + "ghost/tn-0") + "/customer-managed-status")
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'sendingStatus' failed to "
                        + "satisfy constraint: Member must satisfy enum value set: [ENABLED, REINSTATED, DISABLED]"));
        v2().body("{\"SendingStatus\":\"ENABLED\"}")
                .when().put(entityPath("RESOURCE", PREFIX + "ghost/tn-0") + "/customer-managed-status")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("ReputationEntity <" + PREFIX + "ghost/tn-0> does not exist"));
    }

    @Test
    @Order(10)
    void list_filterValues() {
        v2().body("{\"Filter\":{\"SENDING_STATUS\":\"ENABLED\",\"ENTITY_TYPE\":\"RESOURCE\"}}")
                .when().post("/v2/email/reputation/entities").then().statusCode(400)
                .body("message", equalTo("Too many filters provided."));
        for (String[] bad : new String[][] {
                {"SENDING_STATUS", "disabled"}, {"ENTITY_TYPE", "resource"}, {"REPUTATION_IMPACT", "none"}}) {
            v2().body("{\"Filter\":{\"" + bad[0] + "\":\"" + bad[1] + "\"}}")
                    .when().post("/v2/email/reputation/entities").then().statusCode(400)
                    .body("__type", equalTo("BadRequestException"))
                    .body("message", equalTo("Invalid reputation entity " + bad[0] + " specified"));
        }
        v2().body("{\"Filter\":{\"REPUTATION_IMPACT\":\"MEDIUM\"}}")
                .when().post("/v2/email/reputation/entities").then().statusCode(200)
                .body("ReputationEntities", empty());
        v2().body("{\"Filter\":{\"FOO\":\"x\"}}")
                .when().post("/v2/email/reputation/entities").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'filter' failed to satisfy "
                        + "constraint: Map keys must satisfy constraint: [Member must satisfy enum value set: "
                        + "[ENTITY_REFERENCE_PREFIX, REPUTATION_IMPACT, ENTITY_TYPE, SENDING_STATUS]]"));
        v2().body("{\"Filter\":{\"SENDING_STATUS\":\"\"}}")
                .when().post("/v2/email/reputation/entities").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'filter' failed to satisfy "
                        + "constraint: Map value must satisfy constraint: [Member must have length less than "
                        + "or equal to 512, Member must have length greater than or equal to 1]"));
        v2().body("{\"Filter\":{\"ENTITY_REFERENCE_PREFIX\":\"" + PREFIX.replace("floci-rep-it-", "FLOCI-REP-IT-") + "\"}}")
                .when().post("/v2/email/reputation/entities").then().statusCode(200)
                .body("ReputationEntities", empty());
    }

    @Test
    @Order(11)
    void list_paging() {
        String token = v2().body(prefixFilter(",\"PageSize\":1"))
                .when().post("/v2/email/reputation/entities").then().statusCode(200)
                .body("ReputationEntities.ReputationEntityReference", contains(arnA))
                .extract().path("NextToken");
        v2().body(prefixFilter(",\"PageSize\":1,\"NextToken\":\"" + token + "\""))
                .when().post("/v2/email/reputation/entities").then().statusCode(200)
                .body("ReputationEntities.ReputationEntityReference", contains(arnB))
                .body("NextToken", nullValue());
        v2().body("{\"PageSize\":1,\"NextToken\":\"" + token + "\"}")
                .when().post("/v2/email/reputation/entities").then().statusCode(400)
                .body("message", equalTo("Pagination token is bound to a different set of filtering parameters"));
        for (String bad : new String[] {"garbage", ""}) {
            v2().body("{\"NextToken\":\"" + bad + "\"}")
                    .when().post("/v2/email/reputation/entities").then().statusCode(400)
                    .body("message", equalTo("Failed to deserialize token. "));
        }
        // The page size is checked before the filter and the token.
        v2().body("{\"PageSize\":0,\"NextToken\":\"garbage\",\"Filter\":{\"SENDING_STATUS\":\"X\"}}")
                .when().post("/v2/email/reputation/entities").then().statusCode(400)
                .body("message", equalTo("The page size must be between 1 and 1000"));
        v2().body("{\"PageSize\":1001}")
                .when().post("/v2/email/reputation/entities").then().statusCode(400)
                .body("message", equalTo("The page size must be between 1 and 1000"));
    }

    @Test
    @Order(12)
    void updateStatus_reinstatedAndEnabledBecomeTheAggregate() {
        for (String status : new String[] {"REINSTATED", "ENABLED"}) {
            v2().body("{\"SendingStatus\":\"" + status + "\"}")
                    .when().put(entityPath("RESOURCE", arnA) + "/customer-managed-status").then().statusCode(200);
            v2().when().get(entityPath("RESOURCE", arnA)).then().statusCode(200)
                    .body("ReputationEntity.CustomerManagedStatus.Status", equalTo(status))
                    .body("ReputationEntity.SendingStatusAggregate", equalTo(status));
        }
    }

    @Test
    @Order(13)
    void deleteTenant_removesTheEntity() {
        v2().body("{\"TenantName\":\"floci-rep-it-b\"}")
                .when().post("/v2/email/tenants/delete").then().statusCode(200);
        v2().when().get(entityPath("RESOURCE", arnB)).then().statusCode(400)
                .body("message", equalTo("ReputationEntity <" + arnB + "> does not exist"));
        v2().body("{\"TenantName\":\"floci-rep-it-a\"}")
                .when().post("/v2/email/tenants/delete").then().statusCode(200);
    }
}
