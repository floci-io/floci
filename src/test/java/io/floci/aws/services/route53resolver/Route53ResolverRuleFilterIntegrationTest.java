package io.floci.aws.services.route53resolver;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

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

    @Test
    void listResolverRulesMatchesADomainWithOrWithoutTheTrailingDot() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ruleId = createRule("rule-dot-" + suffix, "FORWARD", "dot-" + suffix + ".example.com");
        try {
            list("{\"Name\":\"DomainName\",\"Values\":[\"dot-" + suffix + ".example.com.\"]}")
                    .body("ResolverRules.Id", contains(ruleId));
        } finally {
            call("DeleteResolverRule", "{\"ResolverRuleId\":\"" + ruleId + "\"}");
        }
    }

    @Test
    void listResolverRulesRejectsAnEmptyOrOverlongFilterNameOrValue() {
        for (String filter : new String[] {"{\"Name\":\"\",\"Values\":[\"x\"]}",
                "{\"Name\":\"Name\",\"Values\":[\"\"]}",
                "{\"Name\":\"" + "n".repeat(65) + "\",\"Values\":[\"x\"]}",
                "{\"Name\":\"Name\",\"Values\":[\"" + "v".repeat(601) + "\"]}"}) {
            call("ListResolverRules", "{\"Filters\":[" + filter + "]}").statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
        }
    }

    @Test
    void listResolverRulesRejectsMalformedFilters() {
        for (String filters : new String[] {"\"Name\"", "[\"Name\"]",
                "[{\"Name\":\"Name\",\"Values\":\"x\"}]", "[{\"Name\":\"Name\",\"Values\":[1]}]"}) {
            call("ListResolverRules", "{\"Filters\":" + filters + "}").statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
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
        return Route53ResolverRegionScopingIntegrationTest.call(REGION, action, body);
    }
}
