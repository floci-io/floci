package io.github.hectorvent.floci.services.route53resolver;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
    /** Same access key (so the same account) in a second region, for region-scoping tests. */
    private static final String AUTH_HEADER_WEST =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/route53resolver/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return callAs(AUTH_HEADER, action, body);
    }

    private static Response callAs(String authHeader, String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Route53Resolver." + action)
                .header("Authorization", authHeader)
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

    @Test
    void createFirewallDomainList_missingName_returnsValidationException() {
        // The DNS Firewall operations model ValidationException, not the resolver
        // family's InvalidParameterException: CreateFirewallDomainList does not list
        // InvalidParameterException among its errors at all.
        call("CreateFirewallDomainList", "{\"CreatorRequestId\":\"tok-fdl-noname\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    // ---------- CreatorRequestId idempotency ----------

    @Test
    void createFirewallDomainList_replayedCreatorRequestId_returnsOriginalList() {
        String body = "{\"Name\":\"ab-idem-fdl\",\"CreatorRequestId\":\"tok-idem-fdl\"}";

        String first = call("CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");
        String second = call("CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");

        assertEquals(first, second);
        call("ListFirewallDomainLists", "{}")
        .then()
            .statusCode(200)
            .body("FirewallDomainLists.findAll { it.CreatorRequestId == 'tok-idem-fdl' }.size()",
                    equalTo(1));
    }

    @Test
    void createFirewallDomainList_withoutCreatorRequestId_createsDistinctLists() {
        String body = "{\"Name\":\"ab-noidem-fdl\"}";

        String first = call("CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");
        String second = call("CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");

        assertNotEquals(first, second);
    }

    @Test
    void createResolverEndpoint_replayedCreatorRequestId_returnsOriginalEndpoint() {
        String body = "{\"Name\":\"ab-idem-endpoint\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-idem-endpoint\"}";

        String first = call("CreateResolverEndpoint", body)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");
        String second = call("CreateResolverEndpoint", body)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");

        assertEquals(first, second);
        call("ListResolverEndpoints", "{}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoints.findAll { it.CreatorRequestId == 'tok-idem-endpoint' }.size()",
                    equalTo(1));
    }

    @Test
    void createResolverEndpoint_withoutCreatorRequestId_createsDistinctEndpoints() {
        String body = "{\"Name\":\"ab-noidem-endpoint\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}]}";

        String first = call("CreateResolverEndpoint", body)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");
        String second = call("CreateResolverEndpoint", body)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");

        assertNotEquals(first, second);
    }

    @Test
    void createResolverEndpoint_replayedCreatorRequestIdWithInvalidBody_stillValidates() {
        String valid = "{\"Name\":\"ab-idem-validate\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-idem-validate\"}";
        call("CreateResolverEndpoint", valid).then().statusCode(200);

        // Replaying the token does not excuse a malformed request body.
        call("CreateResolverEndpoint", "{\"Name\":\"ab-idem-validate\",\"Direction\":\"INBOUND\","
                + "\"CreatorRequestId\":\"tok-idem-validate\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));

        // ... including an unrecognised Direction.
        call("CreateResolverEndpoint", "{\"Name\":\"ab-idem-validate\",\"Direction\":\"SIDEWAYS\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-idem-validate\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void createResolverRule_replayedCreatorRequestId_returnsOriginalRule() {
        String body = "{\"Name\":\"ab-idem-rule\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-idem-rule.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"tok-idem-rule\"}";

        String first = call("CreateResolverRule", body)
                .then().statusCode(200).extract().path("ResolverRule.Id");
        String second = call("CreateResolverRule", body)
                .then().statusCode(200).extract().path("ResolverRule.Id");

        assertEquals(first, second);
        call("ListResolverRules", "{}")
        .then()
            .statusCode(200)
            .body("ResolverRules.findAll { it.CreatorRequestId == 'tok-idem-rule' }.size()", equalTo(1));
    }

    @Test
    void createResolverRule_withoutCreatorRequestId_createsDistinctRules() {
        String body = "{\"Name\":\"ab-noidem-rule\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-noidem-rule.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}]}";

        String first = call("CreateResolverRule", body)
                .then().statusCode(200).extract().path("ResolverRule.Id");
        String second = call("CreateResolverRule", body)
                .then().statusCode(200).extract().path("ResolverRule.Id");

        assertNotEquals(first, second);
    }

    // ---------- A replayed token with different parameters is a conflict ----------

    @Test
    void createResolverEndpoint_replayedCreatorRequestIdWithDifferentParameters_returnsResourceExists() {
        String token = "tok-conflict-endpoint";
        call("CreateResolverEndpoint", "{\"Name\":\"ab-conflict-endpoint\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"" + token + "\"}")
        .then().statusCode(200);

        // Same token, same region, different (but individually valid) Name: AWS models
        // ResourceExistsException for this rather than silently returning the original.
        call("CreateResolverEndpoint", "{\"Name\":\"ab-conflict-endpoint-renamed\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"" + token + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceExistsException"));
    }

    @Test
    void createResolverEndpoint_replayedCreatorRequestIdWithDifferentIpValues_returnsResourceExists() {
        String token = "tok-conflict-endpoint-ips";
        call("CreateResolverEndpoint", "{\"Name\":\"ab-conflict-ips\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":["
                + "{\"SubnetId\":\"subnet-aaa\",\"Ip\":\"10.0.0.5\"},"
                + "{\"SubnetId\":\"subnet-bbb\",\"Ip\":\"10.0.1.5\"}],"
                + "\"CreatorRequestId\":\"" + token + "\"}")
        .then().statusCode(200);

        // Same COUNT of IP requests, different subnet and IP values. Comparing counts alone
        // would read this as an equivalent replay and silently return the original endpoint.
        call("CreateResolverEndpoint", "{\"Name\":\"ab-conflict-ips\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":["
                + "{\"SubnetId\":\"subnet-ccc\",\"Ip\":\"10.0.2.5\"},"
                + "{\"SubnetId\":\"subnet-ddd\",\"Ip\":\"10.0.3.5\"}],"
                + "\"CreatorRequestId\":\"" + token + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceExistsException"));
    }

    @Test
    void createResolverEndpoint_replayedCreatorRequestIdWithIdenticalIps_returnsTheOriginal() {
        String token = "tok-replay-endpoint-ips";
        String body = "{\"Name\":\"ab-replay-ips\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":["
                + "{\"SubnetId\":\"subnet-aaa\",\"Ip\":\"10.0.0.5\"},"
                + "{\"SubnetId\":\"subnet-bbb\",\"Ip\":\"10.0.1.5\"}],"
                + "\"CreatorRequestId\":\"" + token + "\"}";

        String first = call("CreateResolverEndpoint", body)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");

        // A genuine retry must still be idempotent — the stricter comparison must not turn
        // every retry into a conflict.
        call("CreateResolverEndpoint", body)
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", equalTo(first));
    }

    @Test
    void createResolverEndpoint_replayedWithReorderedFieldsWithinIpRequests_returnsTheOriginal() {
        // Same two IP requests, but the members are written in a different order inside each
        // object. JSON object member order is not significant, so this is the same request.
        // The pair is chosen so that ordering the entries by their serialised form flips:
        // by SubnetId, subnet-aaa sorts first; by Ip, 10.0.0.1 sorts first — opposite entries.
        String token = "tok-field-order";
        String subnetFirst = "{\"Name\":\"ab-field-order\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":["
                + "{\"SubnetId\":\"subnet-zzz\",\"Ip\":\"10.0.0.1\"},"
                + "{\"SubnetId\":\"subnet-aaa\",\"Ip\":\"10.9.9.9\"}],"
                + "\"CreatorRequestId\":\"" + token + "\"}";
        String ipFirst = "{\"Name\":\"ab-field-order\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":["
                + "{\"Ip\":\"10.0.0.1\",\"SubnetId\":\"subnet-zzz\"},"
                + "{\"Ip\":\"10.9.9.9\",\"SubnetId\":\"subnet-aaa\"}],"
                + "\"CreatorRequestId\":\"" + token + "\"}";

        String first = call("CreateResolverEndpoint", subnetFirst)
                .then().statusCode(200).extract().path("ResolverEndpoint.Id");

        call("CreateResolverEndpoint", ipFirst)
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", equalTo(first));
    }

    @Test
    void createResolverEndpoint_replayResponseOmitsInternalIpFingerprint() {
        String token = "tok-replay-endpoint-nofingerprint";
        call("CreateResolverEndpoint", "{\"Name\":\"ab-nofingerprint\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-aaa\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"" + token + "\"}")
        .then()
            .statusCode(200)
            // ResolverEndpoint models IpAddressCount but no IP list, so whatever we retain to
            // detect a changed IP set must not reach the wire.
            .body("ResolverEndpoint.IpAddressCount", equalTo(1))
            .body("ResolverEndpoint.any { it.key == 'IpAddressRequests' }", equalTo(false))
            .body("ResolverEndpoint.any { it.key == 'IpAddresses' }", equalTo(false));
    }

    @Test
    void createResolverRule_replayedCreatorRequestIdWithDifferentParameters_returnsResourceExists() {
        String token = "tok-conflict-rule";
        call("CreateResolverRule", "{\"Name\":\"ab-conflict-rule\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-conflict.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"" + token + "\"}")
        .then().statusCode(200);

        call("CreateResolverRule", "{\"Name\":\"ab-conflict-rule\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-conflict-different.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"" + token + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceExistsException"));
    }

    @Test
    void createFirewallDomainList_replayedCreatorRequestIdWithDifferentParameters_returnsTheOriginal() {
        // Deliberate divergence, pinned so it cannot drift silently: CreateFirewallDomainList
        // models no conflict error at all (LimitExceeded / Validation / AccessDenied /
        // InternalServiceError / Throttling only), so there is no faithful way to report a
        // conflicting retry here. The lenient replay stands rather than inventing an error
        // code. See issues/route53resolver-firewall-domain-list-retry-conflict.md.
        String token = "tok-conflict-fdl";
        String first = call("CreateFirewallDomainList",
                "{\"Name\":\"ab-conflict-fdl\",\"CreatorRequestId\":\"" + token + "\"}")
                .then().statusCode(200).extract().path("FirewallDomainList.Id");

        call("CreateFirewallDomainList",
                "{\"Name\":\"ab-conflict-fdl-renamed\",\"CreatorRequestId\":\"" + token + "\"}")
        .then()
            .statusCode(200)
            .body("FirewallDomainList.Id", equalTo(first))
            .body("FirewallDomainList.Name", equalTo("ab-conflict-fdl"));
    }

    // ---------- CreatorRequestId replay is scoped to one region ----------

    @Test
    void createFirewallDomainList_sameCreatorRequestIdInAnotherRegion_createsRegionalList() {
        String body = "{\"Name\":\"ab-xregion-fdl\",\"CreatorRequestId\":\"tok-xregion-fdl\"}";

        String east = callAs(AUTH_HEADER, "CreateFirewallDomainList", body)
                .then().statusCode(200)
                .body("FirewallDomainList.Arn", startsWith("arn:aws:route53resolver:us-east-1:"))
                .extract().path("FirewallDomainList.Id");

        callAs(AUTH_HEADER_WEST, "CreateFirewallDomainList", body)
        .then()
            .statusCode(200)
            // A replay in another region must not hand back us-east-1's resource.
            .body("FirewallDomainList.Id", not(equalTo(east)))
            .body("FirewallDomainList.Arn", startsWith("arn:aws:route53resolver:us-west-2:"));
    }

    @Test
    void createResolverEndpoint_sameCreatorRequestIdInAnotherRegion_createsRegionalEndpoint() {
        String body = "{\"Name\":\"ab-xregion-endpoint\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-xregion-endpoint\"}";

        String east = callAs(AUTH_HEADER, "CreateResolverEndpoint", body)
                .then().statusCode(200)
                .body("ResolverEndpoint.Arn", startsWith("arn:aws:route53resolver:us-east-1:"))
                .extract().path("ResolverEndpoint.Id");

        callAs(AUTH_HEADER_WEST, "CreateResolverEndpoint", body)
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", not(equalTo(east)))
            .body("ResolverEndpoint.Arn", startsWith("arn:aws:route53resolver:us-west-2:"));
    }

    @Test
    void createResolverRule_sameCreatorRequestIdInAnotherRegion_createsRegionalRule() {
        String body = "{\"Name\":\"ab-xregion-rule\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-xregion-rule.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"tok-xregion-rule\"}";

        String east = callAs(AUTH_HEADER, "CreateResolverRule", body)
                .then().statusCode(200)
                .body("ResolverRule.Arn", startsWith("arn:aws:route53resolver:us-east-1:"))
                .extract().path("ResolverRule.Id");

        callAs(AUTH_HEADER_WEST, "CreateResolverRule", body)
        .then()
            .statusCode(200)
            .body("ResolverRule.Id", not(equalTo(east)))
            .body("ResolverRule.Arn", startsWith("arn:aws:route53resolver:us-west-2:"));
    }

    @Test
    void createFirewallDomainList_replayWithinTheSameRegion_stillReturnsTheOriginal() {
        String body = "{\"Name\":\"ab-sameregion-fdl\",\"CreatorRequestId\":\"tok-sameregion-fdl\"}";

        String first = callAs(AUTH_HEADER_WEST, "CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");
        String second = callAs(AUTH_HEADER_WEST, "CreateFirewallDomainList", body)
                .then().statusCode(200).extract().path("FirewallDomainList.Id");

        assertEquals(first, second);
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
    void createResolverEndpoint_inboundGetsInboundIdPrefix() {
        call("CreateResolverEndpoint", "{\"Name\":\"ab-endpoint-inbound\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.5\"}],\"CreatorRequestId\":\"tok-endpoint-inbound\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Direction", equalTo("INBOUND"))
            .body("ResolverEndpoint.Id", startsWith("rslvr-in-"));
    }

    @Test
    void createResolverEndpoint_outboundGetsOutboundIdPrefix() {
        call("CreateResolverEndpoint", "{\"Name\":\"ab-endpoint-outbound\",\"Direction\":\"OUTBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.6\"}],\"CreatorRequestId\":\"tok-endpoint-outbound\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Direction", equalTo("OUTBOUND"))
            .body("ResolverEndpoint.Id", startsWith("rslvr-out-"));
    }

    @Test
    void createResolverEndpoint_unknownDirection_returnsInvalidParameters() {
        call("CreateResolverEndpoint", "{\"Name\":\"ab-endpoint-sideways\",\"Direction\":\"SIDEWAYS\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddressRequests\":[{\"SubnetId\":\"subnet-abc\","
                + "\"Ip\":\"10.0.0.7\"}],\"CreatorRequestId\":\"tok-endpoint-sideways\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void createResolverEndpoint_missingIpAddressRequests_returnsInvalidParameters() {
        call("CreateResolverEndpoint", "{\"Name\":\"ab-endpoint-noip\",\"Direction\":\"INBOUND\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"CreatorRequestId\":\"tok-noip\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
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
    void updateResolverEndpoint_unknownEndpointType_returnsInvalidParameters() {
        String id = createEndpoint("ab-endpoint-badtype");

        // ResolverEndpointType is an enum of IPV6 / IPV4 / DUALSTACK: storing anything
        // else would have GetResolverEndpoint report an endpoint type AWS never allows.
        call("UpdateResolverEndpoint", "{\"ResolverEndpointId\":\"" + id
                + "\",\"ResolverEndpointType\":\"IPV5\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));

        call("GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.ResolverEndpointType", equalTo(null));
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
    void createResolverRule_emptyTargetIps_returnsInvalidParameters() {
        // The model pins TargetIps to list min 1: present-but-empty is invalid.
        // Absent TargetIps stays allowed (SYSTEM rules carry none).
        call("CreateResolverRule", "{\"Name\":\"ab-rule-emptytargets\",\"RuleType\":\"FORWARD\","
                + "\"DomainName\":\"ab-rule-emptytargets.example.com.\",\"TargetIps\":[],"
                + "\"CreatorRequestId\":\"tok-rule-emptytargets\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void createResolverRule_unknownRuleType_returnsInvalidParameters() {
        // RuleTypeOption is FORWARD / SYSTEM / RECURSIVE / DELEGATE. An unmodelled value
        // was stored verbatim and echoed back by Get/ListResolverRules as a real rule.
        call("CreateResolverRule", "{\"Name\":\"ab-rule-badtype\",\"RuleType\":\"SIDEWAYS\","
                + "\"DomainName\":\"ab-rule-badtype.example.com.\","
                + "\"TargetIps\":[{\"Ip\":\"10.0.0.1\",\"Port\":53}],"
                + "\"CreatorRequestId\":\"tok-rule-badtype\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));

        call("ListResolverRules", "{}")
        .then()
            .statusCode(200)
            .body("ResolverRules.Name", not(hasItem("ab-rule-badtype")));
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
