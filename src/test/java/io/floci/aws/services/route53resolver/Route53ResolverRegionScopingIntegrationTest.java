package io.floci.aws.services.route53resolver;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.Route53ResolverRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Route 53 Resolver's firewall domain lists, endpoints, rules and rule associations are Regional: each is listed and
 * reached only in the Region it was created in, an association in its rule's. Another Region's id answers
 * {@code ResourceNotFoundException}, as for an id that doesn't exist. A rule is deleted only once no VPC is associated
 * with it, so an association always has its rule's Region.
 */
@QuarkusTest
class Route53ResolverRegionScopingIntegrationTest {

    private static final String HOME = "eu-west-1";
    private static final String OTHER = "eu-central-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aFirewallDomainListIsListedAndReachedOnlyInItsRegion() {
        String name = "fdl-" + UUID.randomUUID().toString().substring(0, 8);
        String id = call(HOME, "CreateFirewallDomainList", "{\"Name\":\"" + name + "\","
                + "\"CreatorRequestId\":\"" + name + "\"}").statusCode(200).extract().path("FirewallDomainList.Id");
        try {
            call(OTHER, "ListFirewallDomainLists", "{}").statusCode(200)
                    .body("FirewallDomainLists.Id", not(hasItem(id)));
            notFound(call(OTHER, "GetFirewallDomainList", "{\"FirewallDomainListId\":\"" + id + "\"}"));
            notFound(call(OTHER, "DeleteFirewallDomainList", "{\"FirewallDomainListId\":\"" + id + "\"}"));

            call(HOME, "ListFirewallDomainLists", "{}").statusCode(200).body("FirewallDomainLists.Id", hasItem(id));
            call(HOME, "GetFirewallDomainList", "{\"FirewallDomainListId\":\"" + id + "\"}").statusCode(200)
                    .body("FirewallDomainList.Name", equalTo(name));
        } finally {
            call(HOME, "DeleteFirewallDomainList", "{\"FirewallDomainListId\":\"" + id + "\"}");
        }
    }

    @Test
    void anEndpointARuleAndItsAssociationAreListedAndReachedOnlyInTheirRegion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String vpcId = "vpc-" + suffix + "abcdef01";
        String endpointId = call(HOME, "CreateResolverEndpoint", "{\"Name\":\"ep-" + suffix + "\","
                + "\"Direction\":\"OUTBOUND\",\"CreatorRequestId\":\"ep-" + suffix + "\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],\"IpAddresses\":[{\"SubnetId\":\"subnet-0\"},"
                + "{\"SubnetId\":\"subnet-1\"}]}").statusCode(200).extract().path("ResolverEndpoint.Id");
        String ruleId = call(HOME, "CreateResolverRule", "{\"CreatorRequestId\":\"rule-" + suffix + "\","
                + "\"Name\":\"rule-" + suffix + "\",\"RuleType\":\"FORWARD\",\"DomainName\":\"" + suffix
                + ".internal\",\"TargetIps\":[{\"Ip\":\"10.0.0.10\"}],\"ResolverEndpointId\":\"" + endpointId + "\"}")
                .statusCode(200).extract().path("ResolverRule.Id");
        String association = "{\"ResolverRuleId\":\"" + ruleId + "\",\"VPCId\":\"" + vpcId + "\"}";
        try {
            String associationId = call(HOME, "AssociateResolverRule", association).statusCode(200)
                    .extract().path("ResolverRuleAssociation.Id");

            call(OTHER, "ListResolverEndpoints", "{}").statusCode(200).body("ResolverEndpoints.Id", not(hasItem(endpointId)));
            notFound(call(OTHER, "GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + endpointId + "\"}"));
            notFound(call(OTHER, "ListResolverEndpointIpAddresses", "{\"ResolverEndpointId\":\"" + endpointId + "\"}"));
            call(OTHER, "ListResolverRules", "{}").statusCode(200).body("ResolverRules.Id", not(hasItem(ruleId)));
            notFound(call(OTHER, "GetResolverRule", "{\"ResolverRuleId\":\"" + ruleId + "\"}"));
            notFound(call(OTHER, "AssociateResolverRule", association));
            call(OTHER, "ListResolverRuleAssociations", "{}").statusCode(200)
                    .body("ResolverRuleAssociations.Id", not(hasItem(associationId)));
            notFound(call(OTHER, "GetResolverRuleAssociation", "{\"ResolverRuleAssociationId\":\"" + associationId + "\"}"));
            notFound(call(OTHER, "DisassociateResolverRule", association));
            notFound(call(OTHER, "DeleteResolverRule", "{\"ResolverRuleId\":\"" + ruleId + "\"}"));
            notFound(call(OTHER, "DeleteResolverEndpoint", "{\"ResolverEndpointId\":\"" + endpointId + "\"}"));

            call(HOME, "ListResolverRuleAssociations", "{}").statusCode(200)
                    .body("ResolverRuleAssociations.Id", hasItem(associationId));
            call(HOME, "DisassociateResolverRule", association).statusCode(200);
        } finally {
            call(HOME, "DeleteResolverRule", "{\"ResolverRuleId\":\"" + ruleId + "\"}");
            call(HOME, "DeleteResolverEndpoint", "{\"ResolverEndpointId\":\"" + endpointId + "\"}");
        }
    }

    @Test
    void aRuleIsDeletedOnlyOnceNoVpcIsAssociatedWithIt() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ruleId = call(HOME, "CreateResolverRule", "{\"CreatorRequestId\":\"sys-" + suffix + "\","
                + "\"Name\":\"sys-" + suffix + "\",\"RuleType\":\"SYSTEM\",\"DomainName\":\"" + suffix
                + ".internal\"}").statusCode(200).extract().path("ResolverRule.Id");
        String byId = "{\"ResolverRuleId\":\"" + ruleId + "\"}";
        String association = "{\"ResolverRuleId\":\"" + ruleId + "\",\"VPCId\":\"vpc-" + suffix + "abcdef01\"}";
        try {
            call(HOME, "AssociateResolverRule", association).statusCode(200);

            call(HOME, "DeleteResolverRule", byId).statusCode(400)
                    .body("__type", equalTo("ResourceInUseException"));
            call(HOME, "GetResolverRule", byId).statusCode(200);

            call(HOME, "DisassociateResolverRule", association).statusCode(200);
            call(HOME, "DeleteResolverRule", byId).statusCode(200);
            notFound(call(HOME, "GetResolverRule", byId));
        } finally {
            call(HOME, "DisassociateResolverRule", association);
            call(HOME, "DeleteResolverRule", byId);
        }
    }

    private static ValidatableResponse call(String region, String action, String body) {
        String authorization = "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region
                + "/route53resolver/aws4_request";
        return Route53ResolverRequests.call(action, body, authorization);
    }

    private static void notFound(ValidatableResponse response) {
        response.body("__type", equalTo("ResourceNotFoundException"));
    }
}
