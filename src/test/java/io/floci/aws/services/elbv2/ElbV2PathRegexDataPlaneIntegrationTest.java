package io.floci.aws.services.elbv2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RdsMockProfile;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ElasticLoadBalancingV2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Rule;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RuleCondition;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.elbv2.PathRegexFixtures.MATCH_PATTERNS;
import static io.floci.aws.testing.AlbFixtures.createListener;
import static io.floci.aws.testing.AlbFixtures.createLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.createTargetGroup;
import static io.floci.aws.testing.AlbFixtures.deleteLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.deleteTargetGroup;
import static io.floci.aws.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public ALB path-regex routing through local HTTP targets. */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class ElbV2PathRegexDataPlaneIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261122/us-east-1/elasticloadbalancing/aws4_request";

    @Inject
    Vertx vertx;

    private HttpServer primary;
    private HttpServer secondary;
    private String loadBalancerArn;
    private String primaryTg;
    private String secondaryTg;
    private String listenerArn;
    private int listenerPort;

    @BeforeEach
    void startTargetsAndListener() throws Exception {
        primary = backend("primary");
        secondary = backend("secondary");
        listenerPort = FreePorts.anyInterfacePort();
        loadBalancerArn = createLoadBalancer("regex-split-lb");
        primaryTg = createTargetGroup("regex-primary", "ip", primary.actualPort());
        secondaryTg = createTargetGroup("regex-secondary", "ip", secondary.actualPort());
        registerTarget(primaryTg, "127.0.0.1", primary.actualPort());
        registerTarget(secondaryTg, "127.0.0.1", secondary.actualPort());
        listenerArn = createListener(loadBalancerArn, primaryTg, listenerPort);
        await().atMost(Duration.ofSeconds(3)).ignoreExceptions()
                .untilAsserted(() -> assertEquals("primary", get("/api/items").asString()));
    }

    @AfterEach
    void cleanUp() {
        assertAll(
                () -> deleteLoadBalancer(loadBalancerArn),
                () -> deleteTargetGroup(primaryTg),
                () -> deleteTargetGroup(secondaryTg),
                () -> closeBackend(primary),
                () -> closeBackend(secondary));
    }

    @Test
    void regexRuleSendsMatchingPathsToTheWeightedTargetGroups() {
        String ruleArn = createSplitRule(0, 100);

        for (int i = 0; i < 20; i++) {
            assertEquals("secondary", get("/api/items").asString());
            assertEquals("secondary", get("/status/200").asString());
            assertEquals("secondary", get("/api/orders/42").asString());
        }
        assertEquals("primary", get("/assets/logo").asString());
        assertEquals("primary", get("/health").asString());
        // The path, not the query string, is matched.
        assertEquals("primary", get("/assets/logo?q=/api/items").asString());

        modifySplitRule(ruleArn, 70, 30);
        int toSecondary = 0;
        int samples = 400;
        for (int i = 0; i < samples; i++) {
            if ("secondary".equals(get("/api/items").asString())) {
                toSecondary++;
            }
        }
        assertTrue(toSecondary > samples * 0.15 && toSecondary < samples * 0.45,
                "expected about 30% on the secondary, got " + toSecondary + " of " + samples);

        modifySplitRule(ruleArn, 100, 0);
        for (int i = 0; i < 20; i++) {
            assertEquals("primary", get("/api/items").asString());
        }
    }

    @Test
    void describeRulesReturnsRegexValuesToTheSdk() {
        createSplitRule(70, 30);
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            Rule rule = client.describeRules(r -> r.listenerArn(listenerArn)).rules().stream()
                    .filter(r -> "75".equals(r.priority())).findFirst().orElseThrow();
            RuleCondition condition = rule.conditions().get(0);
            assertEquals("path-pattern", condition.field());
            assertEquals(MATCH_PATTERNS, condition.pathPatternConfig().regexValues());
            assertTrue(condition.pathPatternConfig().values().isEmpty());
            assertEquals(List.of(70, 30), rule.actions().get(0).forwardConfig().targetGroups().stream()
                    .map(t -> t.weight()).toList());

            String sdkRule = client.createRule(r -> r.listenerArn(listenerArn).priority(80)
                    .conditions(c -> c.field("path-pattern").pathPatternConfig(p -> p.regexValues("^/sdk/[0-9]+$")))
                    .actions(a -> a.type("forward").targetGroupArn(secondaryTg)))
                    .rules().get(0).ruleArn();
            assertEquals(List.of("^/sdk/[0-9]+$"), client.describeRules(r -> r.ruleArns(sdkRule)).rules().get(0)
                    .conditions().get(0).pathPatternConfig().regexValues());
            assertEquals("secondary", get("/sdk/42").asString());
            assertEquals("primary", get("/sdk/x").asString());
        }
    }

    @Test
    void valuesAndRegexValuesTogetherAreRejected() {
        given().header("Authorization", AUTH)
                .formParam("Action", "CreateRule")
                .formParam("ListenerArn", listenerArn)
                .formParam("Priority", "90")
                .formParam("Conditions.member.1.Field", "path-pattern")
                .formParam("Conditions.member.1.PathPatternConfig.Values.member.1", "/a/*")
                .formParam("Conditions.member.1.PathPatternConfig.RegexValues.member.1", "^/a/.*$")
                .formParam("Actions.member.1.Type", "forward")
                .formParam("Actions.member.1.TargetGroupArn", secondaryTg)
                .post("/")
                .then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void sdkRoundTripsXmlCharactersAndCanSwitchBetweenRegexAndGlobConditions() {
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            String regex = "^/api/(a&b|<item>)$";
            Rule created = client.createRule(r -> r.listenerArn(listenerArn).priority(80)
                    .conditions(c -> c.field("path-pattern").pathPatternConfig(p -> p.regexValues(regex)))
                    .actions(a -> a.type("forward").targetGroupArn(secondaryTg))).rules().get(0);
            assertEquals(List.of(regex), created.conditions().get(0).pathPatternConfig().regexValues());
            assertEquals(List.of(regex), client.describeRules(r -> r.ruleArns(created.ruleArn())).rules().get(0)
                    .conditions().get(0).pathPatternConfig().regexValues());
            assertEquals("secondary", get("/api/a&b").asString());

            Rule modified = client.modifyRule(r -> r.ruleArn(created.ruleArn())
                    .conditions(c -> c.field("path-pattern").pathPatternConfig(p -> p.values("/assets/*"))))
                    .rules().get(0);
            assertTrue(modified.conditions().get(0).pathPatternConfig().regexValues().isEmpty());
            assertEquals("secondary", get("/assets/logo").asString());
            assertEquals("primary", get("/api/a&b").asString());

            Rule restored = client.modifyRule(r -> r.ruleArn(created.ruleArn())
                    .conditions(c -> c.field("path-pattern").pathPatternConfig(p -> p.regexValues(regex))))
                    .rules().get(0);
            assertEquals(List.of(regex), restored.conditions().get(0).pathPatternConfig().regexValues());
            assertTrue(restored.conditions().get(0).pathPatternConfig().values().isEmpty());
            assertEquals("secondary", get("/api/a&b").asString());
            assertEquals("primary", get("/assets/logo").asString());
        }
    }

    @Test
    void unsupportedSyntaxCannotCreateOrModifySdkRules() {
        String ruleArn = createSplitRule(0, 100);
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            int initialCount = client.describeRules(r -> r.listenerArn(listenerArn)).rules().size();
            for (String regex : List.of("^/a(?=b)$", "^/(?>a)$", "^/\\p{L}+$")) {
                ElasticLoadBalancingV2Exception createError = assertThrows(ElasticLoadBalancingV2Exception.class,
                        () -> client.createRule(r -> r.listenerArn(listenerArn).priority(90)
                                .conditions(c -> c.field("path-pattern").pathPatternConfig(p -> p.regexValues(regex)))
                                .actions(a -> a.type("forward").targetGroupArn(secondaryTg))));
                assertEquals("ValidationError", createError.awsErrorDetails().errorCode());
                ElasticLoadBalancingV2Exception updateError = assertThrows(ElasticLoadBalancingV2Exception.class,
                        () -> client.modifyRule(r -> r.ruleArn(ruleArn)
                                .conditions(c -> c.field("path-pattern").pathPatternConfig(p -> p.regexValues(regex)))));
                assertEquals("ValidationError", updateError.awsErrorDetails().errorCode());
            }
            assertEquals(initialCount, client.describeRules(r -> r.listenerArn(listenerArn)).rules().size());
            assertEquals(MATCH_PATTERNS, client.describeRules(r -> r.ruleArns(ruleArn))
                    .rules().get(0).conditions().get(0).pathPatternConfig().regexValues());
            assertEquals("secondary", get("/api/items").asString());
        }
    }

    @Test
    void sdkRejectsMisplacedRegexAndEvaluationOverflowBeforeMutation() {
        String ruleArn = createSplitRule(0, 100);
        RuleCondition regex = RuleCondition.builder().field("path-pattern")
                .pathPatternConfig(p -> p.regexValues("/a", "/b", "/c")).build();
        RuleCondition fourRegexes = RuleCondition.builder().field("path-pattern")
                .pathPatternConfig(p -> p.regexValues("/a", "/b", "/c", "/d")).build();
        RuleCondition misplaced = RuleCondition.builder().field("host-header")
                .pathPatternConfig(p -> p.regexValues("(")).build();
        RuleCondition threeHosts = RuleCondition.builder().field("host-header")
                .hostHeaderConfig(h -> h.values("a.example.com", "b.example.com", "c.example.com")).build();
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            int initialCount = client.describeRules(r -> r.listenerArn(listenerArn)).rules().size();
            List<List<RuleCondition>> rejectedConditions = List.of(
                    List.of(misplaced), List.of(fourRegexes), List.of(regex, threeHosts));
            List<String> expectedMessages = List.of(
                    "PathPatternConfig.RegexValues requires the path-pattern field.",
                    "A condition can have at most three match evaluations.",
                    "A rule can have at most five match evaluations.");
            for (int i = 0; i < rejectedConditions.size(); i++) {
                List<RuleCondition> conditions = rejectedConditions.get(i);
                ElasticLoadBalancingV2Exception createError = assertThrows(ElasticLoadBalancingV2Exception.class,
                        () -> client.createRule(r -> r.listenerArn(listenerArn).priority(90).conditions(conditions)
                                .actions(a -> a.type("forward").targetGroupArn(secondaryTg))));
                assertEquals(400, createError.statusCode());
                assertEquals("ValidationError", createError.awsErrorDetails().errorCode());
                assertEquals(expectedMessages.get(i), createError.awsErrorDetails().errorMessage());
                ElasticLoadBalancingV2Exception updateError = assertThrows(ElasticLoadBalancingV2Exception.class,
                        () -> client.modifyRule(r -> r.ruleArn(ruleArn).conditions(conditions)));
                assertEquals(400, updateError.statusCode());
                assertEquals("ValidationError", updateError.awsErrorDetails().errorCode());
                assertEquals(expectedMessages.get(i), updateError.awsErrorDetails().errorMessage());
            }
            assertEquals(initialCount, client.describeRules(r -> r.listenerArn(listenerArn)).rules().size());
            assertEquals(MATCH_PATTERNS, client.describeRules(r -> r.ruleArns(ruleArn))
                    .rules().get(0).conditions().get(0).pathPatternConfig().regexValues());

            RuleCondition twoHosts = threeHosts.toBuilder()
                    .hostHeaderConfig(h -> h.values("a.example.com", "b.example.com")).build();
            Rule accepted = client.createRule(r -> r.listenerArn(listenerArn).priority(90)
                    .conditions(regex, twoHosts).actions(a -> a.type("forward").targetGroupArn(secondaryTg)))
                    .rules().get(0);
            assertEquals(List.of("/a", "/b", "/c"), accepted.conditions().get(0).pathPatternConfig().regexValues());
            Rule updated = client.modifyRule(r -> r.ruleArn(ruleArn).conditions(regex, twoHosts)).rules().get(0);
            assertEquals(accepted.conditions(), updated.conditions());
        }
    }

    @Test
    void nestedRepetitionRoutesLongNonmatchingRequestsWithoutBacktracking() {
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            client.createRule(r -> r.listenerArn(listenerArn).priority(80)
                    .conditions(c -> c.field("path-pattern").pathPatternConfig(p -> p.regexValues("^/(a+)+$")))
                    .actions(a -> a.type("forward").targetGroupArn(secondaryTg)));
            assertEquals("secondary", get("/aaaa").asString());
            assertTimeout(Duration.ofSeconds(3), () ->
                    assertEquals("primary", get("/" + "a".repeat(2000) + "!").asString()));
        }
    }

    private String createSplitRule(int primaryWeight, int secondaryWeight) {
        RequestSpecification request = given().header("Authorization", AUTH)
                .formParam("Action", "CreateRule")
                .formParam("ListenerArn", listenerArn)
                .formParam("Priority", "75");
        conditionAndAction(request, primaryWeight, secondaryWeight);
        return request.post("/").then().statusCode(200).extract()
                .path("CreateRuleResponse.CreateRuleResult.Rules.member.RuleArn");
    }

    private void modifySplitRule(String ruleArn, int primaryWeight, int secondaryWeight) {
        RequestSpecification request = given().header("Authorization", AUTH)
                .formParam("Action", "ModifyRule")
                .formParam("RuleArn", ruleArn);
        conditionAndAction(request, primaryWeight, secondaryWeight);
        Response response = request.post("/").then().statusCode(200).extract().response();
        assertEquals(MATCH_PATTERNS, response.xmlPath().getList(
                "ModifyRuleResponse.ModifyRuleResult.Rules.member.Conditions.member.PathPatternConfig.RegexValues.member"));
    }

    private void conditionAndAction(RequestSpecification request, int primaryWeight, int secondaryWeight) {
        request.formParam("Conditions.member.1.Field", "path-pattern");
        for (int i = 0; i < MATCH_PATTERNS.size(); i++) {
            request.formParam("Conditions.member.1.PathPatternConfig.RegexValues.member." + (i + 1),
                    MATCH_PATTERNS.get(i));
        }
        request.formParam("Actions.member.1.Type", "forward")
                .formParam("Actions.member.1.ForwardConfig.TargetGroups.member.1.TargetGroupArn", primaryTg)
                .formParam("Actions.member.1.ForwardConfig.TargetGroups.member.1.Weight", primaryWeight)
                .formParam("Actions.member.1.ForwardConfig.TargetGroups.member.2.TargetGroupArn", secondaryTg)
                .formParam("Actions.member.1.ForwardConfig.TargetGroups.member.2.Weight", secondaryWeight);
    }

    private HttpServer backend(String body) throws Exception {
        return vertx.createHttpServer().requestHandler(request -> request.response().end(body))
                .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private void closeBackend(HttpServer backend) throws Exception {
        if (backend != null) {
            backend.close().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
    }

    private Response get(String pathAndQuery) {
        return given().baseUri("http://127.0.0.1").port(listenerPort).urlEncodingEnabled(false).get(pathAndQuery);
    }
}
