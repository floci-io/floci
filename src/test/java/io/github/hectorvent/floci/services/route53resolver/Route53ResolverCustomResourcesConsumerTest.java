package io.github.hectorvent.floci.services.route53resolver;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Wire-level tests for the custom (non-managed) Route 53 Resolver operations
 * added this session: firewall domain list CRUD, resolver endpoint CRUD,
 * resolver rule CRUD, and resolver rule / VPC associations.
 *
 * <p>These are all new persistence-backed additions layered onto the
 * pre-existing, deterministic-id AWS-managed firewall domain list logic in
 * {@link Route53ResolverService}, which is left untouched (see
 * {@link Route53ResolverIntegrationTest} for its coverage).</p>
 */
@QuarkusTest
class Route53ResolverCustomResourcesConsumerTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/route53resolver/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Route53Resolver." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/");
    }

    // ---------- CreateFirewallDomainList / DeleteFirewallDomainList ----------

    @Test
    void createFirewallDomainList_returnsCompleteListAndIsListable() {
        String id = call("CreateFirewallDomainList", "{\"Name\":\"ab-custom-fdl\","
                + "\"CreatorRequestId\":\"tok-create-fdl\"}")
        .then()
            .statusCode(200)
            .body("FirewallDomainList.Name", equalTo("ab-custom-fdl"))
            .body("FirewallDomainList.Status", equalTo("COMPLETE"))
            .extract().path("FirewallDomainList.Id");

        call("GetFirewallDomainList", "{\"FirewallDomainListId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("FirewallDomainList.Id", equalTo(id));

        call("ListFirewallDomainLists", "{}")
        .then()
            .statusCode(200)
            .body("FirewallDomainLists.Id", hasItem(id));
    }

    @Test
    void deleteFirewallDomainList_removesCustomList() {
        String id = call("CreateFirewallDomainList", "{\"Name\":\"ab-delete-fdl\","
                + "\"CreatorRequestId\":\"tok-delete-fdl\"}")
        .then().statusCode(200)
        .extract().path("FirewallDomainList.Id");

        call("DeleteFirewallDomainList", "{\"FirewallDomainListId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("FirewallDomainList.Status", equalTo("DELETING"));

        call("GetFirewallDomainList", "{\"FirewallDomainListId\":\"" + id + "\"}")
        .then()
            .statusCode(404);
    }

    // ---------- Resolver endpoints ----------

    private static String createEndpoint(String name) {
        return call("CreateResolverEndpoint", "{\"Name\":\"" + name + "\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-" + name + "\"}")
        .then().statusCode(200)
        .extract().path("ResolverEndpoint.Id");
    }

    @Test
    void createResolverEndpoint_returnsOperationalEndpoint() {
        call("CreateResolverEndpoint", "{\"Name\":\"ab-endpoint-create\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-endpoint-create\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Name", equalTo("ab-endpoint-create"))
            .body("ResolverEndpoint.Direction", equalTo("INBOUND"))
            .body("ResolverEndpoint.Status", equalTo("OPERATIONAL"))
            .body("ResolverEndpoint.IpAddressCount", equalTo(1));
    }

    @Test
    void createResolverEndpoint_missingIpAddressRequests_returnsInvalidParameters() {
        call("CreateResolverEndpoint", "{\"Name\":\"ab-endpoint-noip\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"CreatorRequestId\":\"tok-noip\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }

    @Test
    void getResolverEndpoint_returnsCreatedEndpoint() {
        String id = createEndpoint("ab-endpoint-get");

        call("GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", equalTo(id));
    }

    @Test
    void getResolverEndpoint_unknownId_returnsResourceNotFound() {
        call("GetResolverEndpoint", "{\"ResolverEndpointId\":\"rslvr-in-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listResolverEndpoints_includesCreatedEndpoint() {
        String id = createEndpoint("ab-endpoint-list");

        call("ListResolverEndpoints", "{}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoints.Id", hasItem(id));
    }

    @Test
    void updateResolverEndpoint_changesName() {
        String id = createEndpoint("ab-endpoint-update-before");

        call("UpdateResolverEndpoint", "{\"ResolverEndpointId\":\"" + id
                + "\",\"Name\":\"ab-endpoint-update-after\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", equalTo(id))
            .body("ResolverEndpoint.Name", equalTo("ab-endpoint-update-after"));
    }

    @Test
    void deleteResolverEndpoint_removesEndpoint() {
        String id = createEndpoint("ab-endpoint-delete");

        call("DeleteResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Status", equalTo("DELETING"));

        call("GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- Resolver rules ----------

    private static String createRule(String name, String domain) {
        return call("CreateResolverRule", "{\"Name\":\"" + name + "\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"" + domain + "\",\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"tok-" + name + "\"}")
        .then().statusCode(200)
        .extract().path("ResolverRule.Id");
    }

    @Test
    void createResolverRule_returnsCompleteRule() {
        call("CreateResolverRule", "{\"Name\":\"ab-rule-create\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"example.com.\",\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"tok-rule-create\"}")
        .then()
            .statusCode(200)
            .body("ResolverRule.Name", equalTo("ab-rule-create"))
            .body("ResolverRule.DomainName", equalTo("example.com."))
            .body("ResolverRule.RuleType", equalTo("FORWARD"))
            .body("ResolverRule.Status", equalTo("COMPLETE"));
    }

    @Test
    void getResolverRule_returnsCreatedRule() {
        String id = createRule("ab-rule-get", "ab-rule-get.example.com.");

        call("GetResolverRule", "{\"ResolverRuleId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverRule.Id", equalTo(id));
    }

    @Test
    void getResolverRule_unknownId_returnsResourceNotFound() {
        call("GetResolverRule", "{\"ResolverRuleId\":\"rslvr-rr-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listResolverRules_includesCreatedRule() {
        String id = createRule("ab-rule-list", "ab-rule-list.example.com.");

        call("ListResolverRules", "{}")
        .then()
            .statusCode(200)
            .body("ResolverRules.Id", hasItem(id));
    }

    @Test
    void updateResolverRule_changesName() {
        String id = createRule("ab-rule-update-before", "ab-rule-update.example.com.");

        call("UpdateResolverRule", "{\"ResolverRuleId\":\"" + id
                + "\",\"Config\":{\"Name\":\"ab-rule-update-after\"}}")
        .then()
            .statusCode(200)
            .body("ResolverRule.Id", equalTo(id))
            .body("ResolverRule.Name", equalTo("ab-rule-update-after"));
    }

    @Test
    void deleteResolverRule_removesRule() {
        String id = createRule("ab-rule-delete", "ab-rule-delete.example.com.");

        call("DeleteResolverRule", "{\"ResolverRuleId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverRule.Status", equalTo("DELETING"));

        call("GetResolverRule", "{\"ResolverRuleId\":\"" + id + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- Resolver rule associations ----------

    @Test
    void associateResolverRule_returnsCompleteAssociation() {
        String ruleId = createRule("ab-assoc-rule", "ab-assoc.example.com.");

        String associationId = call("AssociateResolverRule", "{\"ResolverRuleId\":\"" + ruleId
                + "\",\"VPCId\":\"vpc-abc123\",\"Name\":\"ab-assoc-name\"}")
        .then()
            .statusCode(200)
            .body("ResolverRuleAssociation.ResolverRuleId", equalTo(ruleId))
            .body("ResolverRuleAssociation.VPCId", equalTo("vpc-abc123"))
            .body("ResolverRuleAssociation.Status", equalTo("COMPLETE"))
            .extract().path("ResolverRuleAssociation.Id");

        call("GetResolverRuleAssociation", "{\"ResolverRuleAssociationId\":\"" + associationId + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverRuleAssociation.Id", equalTo(associationId));
    }

    @Test
    void associateResolverRule_unknownRule_returnsResourceNotFound() {
        call("AssociateResolverRule", "{\"ResolverRuleId\":\"rslvr-rr-doesnotexist\","
                + "\"VPCId\":\"vpc-abc123\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listResolverRuleAssociations_includesAssociatedRule() {
        String ruleId = createRule("ab-list-assoc-rule", "ab-list-assoc.example.com.");
        call("AssociateResolverRule", "{\"ResolverRuleId\":\"" + ruleId
                + "\",\"VPCId\":\"vpc-list-assoc\"}")
        .then().statusCode(200);

        call("ListResolverRuleAssociations", "{}")
        .then()
            .statusCode(200)
            .body("ResolverRuleAssociations.ResolverRuleId", hasItem(ruleId));
    }

    @Test
    void disassociateResolverRule_removesAssociation() {
        String ruleId = createRule("ab-disassoc-rule", "ab-disassoc.example.com.");
        call("AssociateResolverRule", "{\"ResolverRuleId\":\"" + ruleId
                + "\",\"VPCId\":\"vpc-disassoc\"}")
        .then().statusCode(200);

        call("DisassociateResolverRule", "{\"ResolverRuleId\":\"" + ruleId
                + "\",\"VPCId\":\"vpc-disassoc\"}")
        .then()
            .statusCode(200)
            .body("ResolverRuleAssociation.Status", equalTo("DELETING"));

        call("ListResolverRuleAssociations", "{}")
        .then()
            .statusCode(200)
            .body("ResolverRuleAssociations.findAll { it.ResolverRuleId == '" + ruleId + "' }.size()",
                    equalTo(0));
    }

    @Test
    void disassociateResolverRule_unknownAssociation_returnsResourceNotFound() {
        call("DisassociateResolverRule", "{\"ResolverRuleId\":\"rslvr-rr-neverassociated\","
                + "\"VPCId\":\"vpc-neverassociated\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
