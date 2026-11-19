package io.floci.aws.services.route53resolver;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;

/**
 * ListResolverRules returns the rules matching every filter, a filter matching on any of its values, and takes the
 * legacy upper-case filter names too.
 */
@QuarkusTest
class Route53ResolverRuleFilterIntegrationTest {

    private static final String REGION = "ap-southeast-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listResolverRulesAppliesFilters() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String domainA = "a-" + suffix + ".example.com.";
        String domainB = "b-" + suffix + ".example.org.";
        String ruleA = createRule("rule-a-" + suffix, "FORWARD", domainA);
        String ruleB = createRule("rule-b-" + suffix, "FORWARD", domainB);
        String ruleC = createRule("rule-c-" + suffix, "SYSTEM", domainA);
        try {
            list("{\"Name\":\"DomainName\",\"Values\":[\"" + domainB + "\"]}").body("ResolverRules.Id", contains(ruleB));
            list("{\"Name\":\"DomainName\",\"Values\":[\"" + domainA + "\",\"" + domainB + "\"]}")
                    .body("ResolverRules.Id", containsInAnyOrder(ruleA, ruleB, ruleC));
            list("{\"Name\":\"DomainName\",\"Values\":[\"" + domainA + "\"]}",
                    "{\"Name\":\"Type\",\"Values\":[\"SYSTEM\"]}").body("ResolverRules.Id", contains(ruleC));
            list("{\"Name\":\"Name\",\"Values\":[\"rule-a-" + suffix + "\"]}").body("ResolverRules.Id", contains(ruleA));
            list("{\"Name\":\"CREATOR_REQUEST_ID\",\"Values\":[\"rule-b-" + suffix + "\"]}")
                    .body("ResolverRules.Id", contains(ruleB));
            list("{\"Name\":\"Status\",\"Values\":[\"DELETING\"]}").body("ResolverRules.Id", empty());
        } finally {
            for (String id : new String[] {ruleA, ruleB, ruleC}) {
                call("DeleteResolverRule", "{\"ResolverRuleId\":\"" + id + "\"}");
            }
        }
    }

    private static String createRule(String name, String type, String domain) {
        return call("CreateResolverRule", "{\"CreatorRequestId\":\"" + name + "\",\"Name\":\"" + name
                + "\",\"RuleType\":\"" + type + "\",\"DomainName\":\"" + domain + "\"}")
                .statusCode(200).extract().path("ResolverRule.Id");
    }

    private static ValidatableResponse list(String... filters) {
        return call("ListResolverRules", "{\"Filters\":[" + String.join(",", filters) + "]}").statusCode(200);
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Route53Resolver." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + REGION
                        + "/route53resolver/aws4_request")
                .body(body)
                .when().post("/").then();
    }
}
