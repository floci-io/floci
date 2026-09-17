package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterService;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterService.MutationOutcome;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterService.MutationResult;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * Provisions an {@code AWS::Logs::MetricFilter} through a CloudFormation stack and reads it back
 * through the Logs API. Ref and physical id are filter names. A name change deletes before create;
 * a failed update restores the complete backing definition;
 * and the stack delete removes the filter.
 */
@QuarkusTest
class CloudFormationLogsMetricFilterIntegrationTest {

    @InjectSpy
    CloudWatchLogsMetricFilterService metricFilterService;

    @Inject
    CloudFormationService cloudFormationService;

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/cloudformation/aws4_request";
    private static final String LOGS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String LOGS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/logs/aws4_request";
    private static final Map<String, Set<String>> PRIOR_EVENTS = new ConcurrentHashMap<>();

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "FilterName": {"Type": "String"},
            "Pattern": {"Type": "String", "Default": "ERROR"}
          },
          "Resources": {
            "Filter": {
              "Type": "AWS::Logs::MetricFilter",
              "Properties": {
                "LogGroupName": "%s",
                "FilterName": {"Ref": "FilterName"},
                "FilterPattern": {"Ref": "Pattern"},
                "MetricTransformations": [{
                  "MetricName": "ErrorCount",
                  "MetricNamespace": "Stack/%s",
                  "MetricValue": "1",
                  "DefaultValue": 0,
                  "Unit": "Count"
                }]
              }
            }%s
          },
          "Outputs": {
            "FilterRef": {"Value": {"Ref": "Filter"}}
          }
        }
        """;

    private static final String UNNAMED_TEMPLATE = """
        {
          "Resources": {
            "Filter": {
              "Type": "AWS::Logs::MetricFilter",
              "Properties": {
                "LogGroupName": "%s",
                "FilterPattern": "{ $.latency = * }",
                "MetricTransformations": [{
                  "MetricName": "Latency",
                  "MetricNamespace": "Stack/%s",
                  "MetricValue": "$.latency",
                  "Dimensions": [{"Key": "Route", "Value": "$.route"}]
                }]
              }
            }
          },
          "Outputs": {
            "FilterRef": {"Value": {"Ref": "Filter"}}
          }
        }
        """;

    /** The log group in a stack of its own, so the filter stacks hold nothing else. */
    private static final String GROUP_TEMPLATE = """
        {
          "Resources": {
            "LogGroup": {
              "Type": "AWS::Logs::LogGroup",
              "Properties": {"LogGroupName": "%s"}
            }
          }
        }
        """;

    /** A resource that fails after the filter, so the update rolls back. */
    private static final String FAILING_RESOURCE = """
        ,
            "BadSecret": {
              "Type": "AWS::SecretsManager::Secret",
              "DependsOn": "Filter",
              "Properties": {
                "Name": "metric-filter-rollback-%s",
                "SecretString": "explicit",
                "GenerateSecretString": {"PasswordLength": 32}
              }
            }""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void filterFollowsTheTemplateThroughCreateUpdateReplacementAndDelete() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-" + suffix;
        String groupStack = "cfn-metric-filter-group-" + suffix;
        String group = "/cfn/metric-filter/" + suffix;
        createGroupStack(groupStack, group);
        String template = TEMPLATE.formatted(group, suffix, "");

        cloudFormation(stack, "CreateStack", template, Map.of("FilterName", "errors"));
        assertEquals("errors", outputValue(describeStacks(stack, "CREATE_COMPLETE"), "FilterRef"));
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].filterName", equalTo("errors"))
            .body("metricFilters[0].filterPattern", equalTo("ERROR"))
            .body("metricFilters[0].metricTransformations[0].metricName", equalTo("ErrorCount"))
            .body("metricFilters[0].metricTransformations[0].metricNamespace", equalTo("Stack/" + suffix))
            .body("metricFilters[0].metricTransformations[0].metricValue", equalTo("1"))
            .body("metricFilters[0].metricTransformations[0].defaultValue", equalTo(0.0f))
            .body("metricFilters[0].metricTransformations[0].unit", equalTo("Count"));

        cloudFormation(stack, "UpdateStack", template, Map.of("FilterName", "errors", "Pattern", "FATAL"));
        assertEquals("errors", outputValue(describeStacks(stack, "UPDATE_COMPLETE"), "FilterRef"),
                "a pattern change keeps the same filter");
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].filterPattern", equalTo("FATAL"));

        cloudFormation(stack, "UpdateStack", template, Map.of("FilterName", "fatal", "Pattern", "FATAL"));
        assertEquals("fatal", outputValue(describeStacks(stack, "UPDATE_COMPLETE"), "FilterRef"),
                "a name change is a replacement");
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].filterName", equalTo("fatal"));
        assertResourceSequence(stack, List.of("errors:UPDATE_IN_PROGRESS", "errors:DELETE_IN_PROGRESS",
                "errors:DELETE_COMPLETE", "fatal:CREATE_IN_PROGRESS", "fatal:CREATE_COMPLETE", "fatal:UPDATE_COMPLETE"));
        assertTrue(describeEvents(stack).contains(
                "Requested update requires the replacement of the existing resource; deleting existing resource, then creating a new one."));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(0));
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    @Test
    void anUnnamedFilterGetsAGeneratedNameAndItsDimensionsReachTheService() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-unnamed-" + suffix;
        String groupStack = "cfn-metric-filter-unnamed-group-" + suffix;
        String group = "/cfn/metric-filter/unnamed/" + suffix;
        createGroupStack(groupStack, group);
        String template = UNNAMED_TEMPLATE.formatted(group, suffix);

        cloudFormation(stack, "CreateStack", template, Map.of());
        String ref = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "FilterRef");
        assertTrue(ref.startsWith(stack + "-Filter-"), ref);
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].metricTransformations[0].metricValue", equalTo("$.latency"))
            .body("metricFilters[0].metricTransformations[0].dimensions.Route", equalTo("$.route"));

        cloudFormation(stack, "UpdateStack", template.replace("$.latency = *", "$.latency > 10"), Map.of());
        assertEquals(ref, outputValue(describeStacks(stack, "UPDATE_COMPLETE"), "FilterRef"),
                "an unnamed filter keeps its generated name");
        describeFilters(group).then().body("metricFilters[0].filterPattern", equalTo("{ $.latency > 10 }"));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    @Test
    void aFailedUpdateRestoresThePatternAnInPlaceUpdateChanged() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-rb-" + suffix;
        String groupStack = "cfn-metric-filter-rb-group-" + suffix;
        String group = "/cfn/metric-filter/rb/" + suffix;
        createGroupStack(groupStack, group);

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(group, suffix, ""), Map.of("FilterName", "errors"));
        describeStacks(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(group, suffix, FAILING_RESOURCE.formatted(suffix)),
                Map.of("FilterName", "errors", "Pattern", "FATAL"));
        assertEquals("errors", outputValue(describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE"), "FilterRef"));
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].filterPattern", equalTo("ERROR"));
        assertMutationPrecedesBadSecret(stack, "errors");
        assertPublishedValue(group, "Stack/" + suffix, "ERROR", 1);

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    @Test
    void aFailedUpdateRollsAReplacementBack() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-rb2-" + suffix;
        String groupStack = "cfn-metric-filter-rb2-group-" + suffix;
        String group = "/cfn/metric-filter/rb2/" + suffix;
        createGroupStack(groupStack, group);

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(group, suffix, ""), Map.of("FilterName", "errors"));
        describeStacks(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(group, suffix, FAILING_RESOURCE.formatted(suffix)),
                Map.of("FilterName", "fatal"));
        assertEquals("errors", outputValue(describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE"), "FilterRef"));
        describeFilters(group).then()
            .statusCode(200)
            .body("metricFilters", hasSize(1))
            .body("metricFilters[0].filterName", equalTo("errors"));
        assertMutationPrecedesBadSecret(stack, "fatal");
        assertPublishedValue(group, "Stack/" + suffix, "ERROR", 1);
        assertResourceSequence(stack, List.of("errors:UPDATE_IN_PROGRESS", "errors:DELETE_IN_PROGRESS",
                "errors:DELETE_COMPLETE", "fatal:CREATE_IN_PROGRESS", "fatal:CREATE_COMPLETE", "fatal:UPDATE_COMPLETE",
                "fatal:UPDATE_IN_PROGRESS", "fatal:DELETE_IN_PROGRESS", "fatal:DELETE_COMPLETE",
                "fatal:CREATE_IN_PROGRESS", "errors:CREATE_COMPLETE", "errors:UPDATE_COMPLETE"));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    @Test
    void invalidReplacementDeletesThenRecreatesPriorDefinitionWithNameOnlyRefAndEvents() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-invalid-replace-" + suffix;
        String groupStack = stack + "-group";
        String group = "/cfn/invalid-replace/" + suffix;
        createGroupStack(groupStack, group);
        String original = TEMPLATE.formatted(group, suffix, "").replace("\"MetricValue\": \"1\"", "\"MetricValue\": \"2\"");
        cloudFormation(stack, "CreateStack", original, Map.of("FilterName", "old", "Pattern", "WARN"));
        describeStacks(stack, "CREATE_COMPLETE");
        Map<String, Object> before = describeFilters(group).jsonPath().getMap("metricFilters[0]");
        cloudFormation(stack, "UpdateStack", original.replace("\"MetricValue\": \"2\"", "\"MetricValue\": \"9\""),
                Map.of("FilterName", "new", "Pattern", "{"));
        assertEquals("old", outputValue(describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE"), "FilterRef"));
        Map<String, Object> after = describeFilters(group).jsonPath().getMap("metricFilters[0]");
        assertNotNull(after, "rollback must restore the deleted backing filter, not just its Ref");
        assertTrue(((Number) after.get("creationTime")).longValue() > ((Number) before.get("creationTime")).longValue());
        after.remove("creationTime");
        before.remove("creationTime");
        assertEquals(before, after);
        assertResourceSequence(stack, List.of("old:UPDATE_IN_PROGRESS", "old:DELETE_IN_PROGRESS", "old:DELETE_COMPLETE",
                "new:CREATE_IN_PROGRESS", "new:CREATE_FAILED", "new:UPDATE_FAILED", "new:UPDATE_IN_PROGRESS",
                "new:DELETE_IN_PROGRESS", "new:DELETE_COMPLETE", "new:CREATE_IN_PROGRESS",
                "old:CREATE_COMPLETE", "old:UPDATE_COMPLETE"));
        assertPublishedValue(group, "Stack/" + suffix, "WARN", 2);
        String template = given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                .formParam("Action", "GetTemplate").formParam("StackName", stack).post("/").asString();
        assertTrue(template.contains("MetricValue"), template);
        assertTrue(template.contains("&quot;2&quot;") || template.contains("\"2\""), template);
        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    @Test
    void resolvedArrayIntrinsicsAndNoValueUseCfnShapes() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-intrinsics-" + suffix;
        String groupStack = stack + "-group";
        String group = "/cfn/intrinsics/" + suffix;
        createGroupStack(groupStack, group);
        String template = """
                {"Parameters":{"Enabled":{"Type":"String","Default":"false"}},
                 "Conditions":{"UseDimensions":{"Fn::Equals":[{"Ref":"Enabled"},"true"]}},
                 "Resources":{"Filter":{"Type":"AWS::Logs::MetricFilter","Properties":{
                   "LogGroupName":"%s", "FilterName":{"Ref":"AWS::NoValue"}, "FilterPattern":"",
                   "ApplyOnTransformedLogs":{"Ref":"Enabled"},
                   "MetricTransformations":{"Fn::If":["UseDimensions",[],[
                      {"MetricName":"ErrorCount","MetricNamespace":"Stack/%s","MetricValue":"1",
                       "Dimensions":{"Fn::If":["UseDimensions",[],{"Ref":"AWS::NoValue"}]}}]]},
                   "EmitSystemFieldDimensions":{"Fn::Split":[",","@aws.account,@aws.region"]}}}},
                 "Outputs":{"FilterRef":{"Value":{"Ref":"Filter"}}}}
                """.formatted(group, suffix);
        cloudFormation(stack, "CreateStack", template, Map.of());
        String ref = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "FilterRef");
        assertTrue(ref.startsWith(stack + "-Filter-"), ref);
        describeFilters(group).then().body("metricFilters[0].applyOnTransformedLogs", equalTo(false))
                .body("metricFilters[0].emitSystemFieldDimensions", hasSize(2));
        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirmedRestore", "uncertainRestore", "uncertainReplacement", "confirmedAddedDelete"})
    void deleteStackPreservesFailedRollbackOwnershipUntilResourceAwareCleanup(String fault) throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-delete-failed-" + suffix;
        String group = "/cfn/external-delete-failed/" + suffix;
        boolean uncertain = fault.startsWith("uncertain");
        boolean added = "confirmedAddedDelete".equals(fault);
        String faultName = added ? "added" : "uncertainReplacement".equals(fault) ? "new" : "old";
        given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                .header("X-Amz-Target", "Logs_20140328.CreateLogGroup")
                .body(Map.of("logGroupName", group)).post("/").then().statusCode(200);
        try {
            given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                    .header("X-Amz-Target", "Logs_20140328.PutMetricFilter")
                    .body(Map.of("logGroupName", group, "filterName", "unrelated", "filterPattern", "UNRELATED",
                            "metricTransformations", List.of(Map.of("metricName", "Unrelated",
                                    "metricNamespace", "Other/" + suffix, "metricValue", "7"))))
                    .post("/").then().statusCode(200);
            Map<String, Object> unrelated = backingFilter(group, "unrelated");
            cloudFormation(stack, "CreateStack", TEMPLATE.formatted(group, suffix, ""),
                    Map.of("FilterName", "old", "Pattern", "WARN"));
            describeStacks(stack, "CREATE_COMPLETE");

            doAnswer(invocation -> {
                MetricFilter definition = invocation.getArgument(0);
                if (added || !group.equals(definition.getLogGroupName()) || !faultName.equals(definition.getFilterName())) {
                    return invocation.callRealMethod();
                }
                assertTrue(metricFilterService.findMetricFilter(group, faultName, "us-east-1").isEmpty());
                if (uncertain) {
                    // Persist the real definition but withhold the ownership acknowledgement,
                    // simulating a write followed by an unavailable ownership inspection.
                    metricFilterService.putMetricFilter(definition, "us-east-1");
                    Consumer<MutationResult> outcome = invocation.getArgument(2);
                    outcome.accept(new MutationResult(MutationOutcome.UNKNOWN, null));
                } else {
                    invocation.callRealMethod();
                }
                throw new AwsException("ServiceUnavailableException", "injected " + fault + " after write", 500);
            }).when(metricFilterService).createMetricFilter(any(), anyString(), any());

            String additions = FAILING_RESOURCE.formatted(suffix);
            if (added) {
                doAnswer(invocation -> {
                    Consumer<MutationResult> outcome = invocation.getArgument(3);
                    outcome.accept(new MutationResult(MutationOutcome.NOT_APPLIED, true));
                    throw new AwsException("ServiceUnavailableException", "injected added-filter rollback deletion", 500);
                }).doCallRealMethod().when(metricFilterService)
                        .deleteMetricFilter(eq(group), eq("added"), eq("us-east-1"), any());
                additions = """
                        ,"AddedFilter":{"Type":"AWS::Logs::MetricFilter","Properties":{
                          "LogGroupName":"%s","FilterName":"added","FilterPattern":"WARN",
                          "MetricTransformations":[{"MetricName":"Added","MetricNamespace":"Stack/%s","MetricValue":"1"}]}}
                        """.formatted(group, suffix)
                        + additions.replace("\"DependsOn\": \"Filter\"", "\"DependsOn\": \"AddedFilter\"");
            }
            cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(group, suffix, additions),
                    Map.of("FilterName", added ? "old" : "new", "Pattern", "FATAL"));
            describeStacks(stack, "UPDATE_ROLLBACK_FAILED");
            StackResource failed = cloudFormationService.describeStacks(stack, "us-east-1")
                    .getFirst().getResources().get(added ? "AddedFilter" : "Filter");
            assertEquals("UPDATE_FAILED", failed.getStatus());
            String snapshot = failed.getAttributes().get(CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR);
            if (added) {
                assertNull(snapshot, "new resources carry create ownership, not an update snapshot");
                assertEquals("true", failed.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
            } else {
                assertNotNull(snapshot);
                String address = "uncertainReplacement".equals(fault) ? "target" : "prior";
                assertEquals(uncertain ? "UNKNOWN" : "OWNED",
                        new ObjectMapper().readTree(snapshot).path(address).path("ownership").asText(), snapshot);
            }
            assertNotNull(backingFilter(group, faultName), "the failed write really created a backing filter");

            if (uncertain) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    assertThrows(ExecutionException.class, () -> cloudFormationService
                            .deleteStack(stack, "us-east-1", "000000000000").get(10, TimeUnit.SECONDS));
                    describeStacks(stack, "DELETE_FAILED");
                    StackResource retained = cloudFormationService.describeStacks(stack, "us-east-1")
                            .getFirst().getResources().get("Filter");
                    assertEquals("DELETE_FAILED", retained.getStatus());
                    assertEquals(snapshot, retained.getAttributes().get(CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR));
                    assertNotNull(backingFilter(group, faultName));
                    assertEquals(unrelated, backingFilter(group, "unrelated"));
                }
                metricFilterService.deleteMetricFilter(group, faultName, "us-east-1");
            }
            assertDoesNotThrow(() -> cloudFormationService.deleteStack(stack, "us-east-1", "000000000000")
                    .get(10, TimeUnit.SECONDS));
            assertTrue(metricFilterService.findMetricFilter(group, "old", "us-east-1").isEmpty(),
                    "DeleteStack must clean the owned restoration before reporting completion");
            assertTrue(metricFilterService.findMetricFilter(group, "new", "us-east-1").isEmpty());
            assertTrue(metricFilterService.findMetricFilter(group, "added", "us-east-1").isEmpty(),
                    "a failed rollback delete of a newly owned resource must also be retried");
            assertEquals(unrelated, backingFilter(group, "unrelated"));
            given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                    .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
                    .body(Map.of("logGroupNamePrefix", group)).post("/").then().statusCode(200)
                    .body("logGroups[0].logGroupName", equalTo(group));
            assertThrows(AwsException.class, () -> cloudFormationService.describeStacks(stack, "us-east-1"));
        } finally {
            doCallRealMethod().when(metricFilterService).createMetricFilter(any(), anyString(), any());
            doCallRealMethod().when(metricFilterService).deleteMetricFilter(anyString(), anyString(), anyString(), any());
            for (String name : List.of("old", "new", "added")) {
                if (metricFilterService.findMetricFilter(group, name, "us-east-1").isPresent()) {
                    metricFilterService.deleteMetricFilter(group, name, "us-east-1");
                }
            }
            cloudFormationService.deleteStack(stack, "us-east-1", "000000000000").get(10, TimeUnit.SECONDS);
            given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                    .header("X-Amz-Target", "Logs_20140328.DeleteLogGroup")
                    .body(Map.of("logGroupName", group)).post("/").then().statusCode(200);
        }
    }

    private static Map<String, Object> backingFilter(String group, String name) {
        List<Map<String, Object>> filters = describeFilters(group).jsonPath().getList("metricFilters");
        return filters.stream().filter(filter -> name.equals(filter.get("filterName"))).findFirst().orElse(null);
    }

    @ParameterizedTest
    @CsvSource({"forward,false", "forward,true", "stack,false", "stack,true"})
    void appliedAndUnknownDeletesDoNotClaimAConcurrentOwnersFilter(String phase, boolean unknown) throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-delete-outcome-" + suffix;
        String group = "/cfn/external-delete-outcome/" + suffix;
        ObjectMapper mapper = new ObjectMapper();
        given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                .header("X-Amz-Target", "Logs_20140328.CreateLogGroup")
                .body(Map.of("logGroupName", group)).post("/").then().statusCode(200);
        try {
            cloudFormation(stack, "CreateStack", TEMPLATE.formatted(group, suffix, ""),
                    Map.of("FilterName", "old", "Pattern", "WARN"));
            describeStacks(stack, "CREATE_COMPLETE");
            MetricFilter original = metricFilterService.findMetricFilter(group, "old", "us-east-1").orElseThrow();
            MetricFilter stable = mapper.convertValue(original, MetricFilter.class);
            stable.setFilterName("unrelated");
            metricFilterService.putMetricFilter(stable, "us-east-1");
            Map<String, Object> unrelated = backingFilter(group, "unrelated");
            AtomicReference<MetricFilter> otherOwner = new AtomicReference<>();
            AtomicBoolean armed = new AtomicBoolean(true);
            doAnswer(invocation -> {
                String requestedGroup = invocation.getArgument(0);
                String requestedName = invocation.getArgument(1);
                if (!group.equals(requestedGroup) || !"old".equals(requestedName) || !armed.compareAndSet(true, false)) {
                    return invocation.callRealMethod();
                }
                if (unknown) {
                    metricFilterService.deleteMetricFilter(group, "old", "us-east-1");
                    Consumer<MutationResult> outcome = invocation.getArgument(3);
                    outcome.accept(new MutationResult(MutationOutcome.UNKNOWN, null));
                } else {
                    invocation.callRealMethod();
                }
                MetricFilter b = mapper.convertValue(original, MetricFilter.class);
                b.setFilterPattern("UNRELATED");
                b.getMetricTransformations().getFirst().setMetricValue("7");
                otherOwner.set(metricFilterService.putMetricFilter(b, "us-east-1"));
                throw new AwsException("ServiceUnavailableException", "injected failure after delete", 500);
            }).when(metricFilterService).deleteMetricFilter(anyString(), anyString(), anyString(), any());

            if ("forward".equals(phase)) {
                cloudFormation(stack, "UpdateStack", TEMPLATE.formatted(group, suffix, ""),
                        Map.of("FilterName", "new", "Pattern", "FATAL"));
                describeStacks(stack, "UPDATE_ROLLBACK_FAILED");
            } else {
                assertThrows(ExecutionException.class, () -> cloudFormationService
                        .deleteStack(stack, "us-east-1", "000000000000").get(10, TimeUnit.SECONDS));
                describeStacks(stack, "DELETE_FAILED");
            }
            assertNotNull(otherOwner.get(), "B must be created after the actual delete and before A handles its failure");
            assertEquals(mapper.valueToTree(otherOwner.get()),
                    mapper.valueToTree(mapper.convertValue(backingFilter(group, "old"), MetricFilter.class)));
            StackResource resource = cloudFormationService.describeStacks(stack, "us-east-1")
                    .getFirst().getResources().get("Filter");
            String metadataKey = "forward".equals(phase) ? CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR
                    : "__FlociMetricFilterState";
            String metadata = resource.getAttributes().get(metadataKey);
            assertNotNull(metadata);
            JsonNode state = mapper.readTree(metadata);
            if ("forward".equals(phase)) {
                state = state.path("prior");
            }
            assertEquals(unknown ? "UNKNOWN" : "UNOWNED", state.path("ownership").asText());
            if (unknown) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    assertThrows(ExecutionException.class, () -> cloudFormationService
                            .deleteStack(stack, "us-east-1", "000000000000").get(10, TimeUnit.SECONDS));
                    describeStacks(stack, "DELETE_FAILED");
                    assertEquals(metadata, cloudFormationService.describeStacks(stack, "us-east-1").getFirst()
                            .getResources().get("Filter").getAttributes().get(metadataKey));
                    assertEquals(mapper.valueToTree(otherOwner.get()),
                            mapper.valueToTree(mapper.convertValue(backingFilter(group, "old"), MetricFilter.class)));
                }
                metricFilterService.deleteMetricFilter(group, "old", "us-east-1");
            }
            assertDoesNotThrow(() -> cloudFormationService.deleteStack(stack, "us-east-1", "000000000000")
                    .get(10, TimeUnit.SECONDS));
            if (!unknown) {
                assertEquals(mapper.valueToTree(otherOwner.get()),
                        mapper.valueToTree(mapper.convertValue(backingFilter(group, "old"), MetricFilter.class)));
            }
            assertEquals(unrelated, backingFilter(group, "unrelated"));
            given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                    .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
                    .body(Map.of("logGroupNamePrefix", group)).post("/").then().statusCode(200)
                    .body("logGroups[0].logGroupName", equalTo(group));
        } finally {
            doCallRealMethod().when(metricFilterService).deleteMetricFilter(anyString(), anyString(), anyString(), any());
            for (String name : List.of("old", "new")) {
                if (metricFilterService.findMetricFilter(group, name, "us-east-1").isPresent()) {
                    metricFilterService.deleteMetricFilter(group, name, "us-east-1");
                }
            }
            cloudFormationService.deleteStack(stack, "us-east-1", "000000000000").get(10, TimeUnit.SECONDS);
            given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                    .header("X-Amz-Target", "Logs_20140328.DeleteLogGroup")
                    .body(Map.of("logGroupName", group)).post("/").then().statusCode(200);
        }
    }

    private static List<Map<String, String>> newEvents(String stack) {
        List<Map<String, String>> events = new ArrayList<>(XmlParser.extractGroups(describeEvents(stack), "member"));
        Collections.reverse(events);
        return events.stream().filter(e -> !PRIOR_EVENTS.getOrDefault(stack, Set.of()).contains(e.get("EventId"))).toList();
    }

    private static void assertResourceSequence(String stack, List<String> expected) {
        List<Map<String, String>> events = newEvents(stack);
        assertEquals(events.size(), events.stream().map(e -> e.get("EventId")).distinct().count());
        assertEquals(expected, events.stream().filter(e -> "Filter".equals(e.get("LogicalResourceId")))
                .map(e -> e.get("PhysicalResourceId") + ":" + e.get("ResourceStatus")).toList());
    }

    private static void assertMutationPrecedesBadSecret(String stack, String physicalId) {
        List<Map<String, String>> events = newEvents(stack);
        int mutation = -1;
        int failure = -1;
        for (int i = 0; i < events.size(); i++) {
            Map<String, String> e = events.get(i);
            if ("Filter".equals(e.get("LogicalResourceId")) && physicalId.equals(e.get("PhysicalResourceId"))
                    && "UPDATE_COMPLETE".equals(e.get("ResourceStatus")) && mutation == -1) {
                mutation = i;
            }
            if ("BadSecret".equals(e.get("LogicalResourceId")) && "UPDATE_FAILED".equals(e.get("ResourceStatus"))) {
                failure = i;
            }
        }
        assertTrue(mutation >= 0 && failure > mutation, events.toString());
    }

    private static void assertPublishedValue(String group, String namespace, String message, int value) {
        long timestamp = System.currentTimeMillis();
        String stream = "verify-" + timestamp;
        given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                .header("X-Amz-Target", "Logs_20140328.CreateLogStream")
                .body(Map.of("logGroupName", group, "logStreamName", stream)).post("/").then().statusCode(200);
        given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                .header("X-Amz-Target", "Logs_20140328.PutLogEvents")
                .body(Map.of("logGroupName", group, "logStreamName", stream,
                        "logEvents", List.of(Map.of("timestamp", timestamp, "message", message))))
                .post("/").then().statusCode(200);
        given().contentType("application/x-amz-json-1.0")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/monitoring/aws4_request")
                .header("X-Amz-Target", "GraniteServiceVersion20100801.GetMetricStatistics")
                .body(Map.of("Namespace", namespace, "MetricName", "ErrorCount", "Period", 60,
                        "StartTime", (timestamp - 60_000) / 1000.0, "EndTime", (timestamp + 60_000) / 1000.0,
                        "Statistics", List.of("Sum", "SampleCount")))
                .post("/").then().statusCode(200)
                .body("Datapoints", hasSize(1)).body("Datapoints[0].Sum", equalTo((float) value))
                .body("Datapoints[0].SampleCount", equalTo(1.0f));
    }

    @Test
    void anInvalidPatternFailsTheResource() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-metric-filter-bad-" + suffix;
        String groupStack = "cfn-metric-filter-bad-group-" + suffix;
        String group = "/cfn/metric-filter/bad/" + suffix;
        createGroupStack(groupStack, group);

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(group, suffix, ""),
                Map.of("FilterName", "bad", "Pattern", "{ $.a = }"));

        String events = awaitStackStatus(stack, "ROLLBACK_COMPLETE");
        assertTrue(events.contains("Invalid filter pattern"), "the status reason carries the parse error: " + events);
        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(groupStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(groupStack);
    }

    private static void createGroupStack(String stack, String group) {
        cloudFormation(stack, "CreateStack", GROUP_TEMPLATE.formatted(group), Map.of());
        describeStacks(stack, "CREATE_COMPLETE");
    }

    private static Response describeFilters(String group) {
        return given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                .header("X-Amz-Target", "Logs_20140328.DescribeMetricFilters")
                .body("{\"logGroupName\":\"" + group + "\"}").post("/");
    }

    private static void cloudFormation(String stack, String action, String templateBody,
                                       Map<String, String> parameters) {
        if ("UpdateStack".equals(action)) {
            PRIOR_EVENTS.put(stack, XmlParser.extractGroups(describeEvents(stack), "member").stream()
                    .map(e -> e.get("EventId")).collect(Collectors.toSet()));
        }
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String stack, String expectedStatus) {
        String body = "";
        for (int i = 0; i < 100; i++) {
            body = given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                    .formParam("Action", "DescribeStacks").formParam("StackName", stack).post("/").asString();
            if (body.contains("<StackStatus>" + expectedStatus + "</StackStatus>")
                    && newEvents(stack).stream().anyMatch(e -> stack.equals(e.get("LogicalResourceId"))
                    && expectedStatus.equals(e.get("ResourceStatus")))) {
                return body;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        return fail("Expected new " + expectedStatus + " for " + stack + ": " + body + "\n" + describeEvents(stack));
    }

    private static String describeEvents(String stack) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static String awaitStackStatus(String stack, String expectedStatus) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
            .when().post("/").then().extract().asString();
            if (body.contains("<StackStatus>" + expectedStatus + "</StackStatus>")) {
                return describeEvents(stack);
            }
            Thread.sleep(50);
        }
        return fail("stack " + stack + " did not reach " + expectedStatus + " within the timeout");
    }

    private static void awaitStackDeleted(String stack) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + stack + " was not deleted within the timeout");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
