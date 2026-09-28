package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.MetricFilter;
import software.amazon.awssdk.services.cloudwatchlogs.model.MetricTransformation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** SDK checks of backing resources and emitted metrics, not just CFN success-shaped status. */
@DisplayName("CloudFormation AWS::Logs::MetricFilter")
class CloudFormationLogsMetricFilterTest {
    private static CloudFormationClient cfn;
    private static CloudWatchLogsClient logs;
    private static CloudWatchClient metrics;
    private String stack;
    private String group;
    private Set<String> priorEvents = Set.of();

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        logs = TestFixtures.cloudWatchLogsClient();
        metrics = TestFixtures.cloudWatchClient();
    }

    @BeforeEach
    void setup() {
        stack = TestFixtures.uniqueName("compat-cfn-metric-filter");
        group = "/test/" + stack;
        logs.createLogGroup(r -> r.logGroupName(group));
        logs.createLogStream(r -> r.logGroupName(group).logStreamName("events"));
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        deleteStack();
        logs.deleteLogGroup(r -> r.logGroupName(group));
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        logs.close();
        metrics.close();
    }

    @Test
    void observedRetainReplacementDeletesFirstAtQuotaAndKeepsUnrelatedFilters() throws InterruptedException {
        String template = template("", "");
        create(template, "errors", "ERROR", "1");
        assertIdentity("errors");
        update(template("Retain", ""), "errors", "WARN", "2", "UPDATE_COMPLETE");
        assertIdentity("errors");
        assertThat(filter("errors").filterPattern()).isEqualTo("WARN");
        assertSample("WARN", 2, null);
        assertThat(cfn.getTemplate(r -> r.stackName(stack)).templateBody()).contains("\"UpdateReplacePolicy\": \"Retain\"");

        for (int i = 0; i < 99; i++) {
            String name = "unrelated-" + i;
            logs.putMetricFilter(r -> r.logGroupName(group).filterName(name).filterPattern("UNRELATED")
                    .metricTransformations(MetricTransformation.builder().metricName("Untouched")
                            .metricNamespace(stack + "/unrelated").metricValue("7").build()));
        }
        Map<String, MetricFilter> unrelated = filters().stream().filter(f -> f.filterName().startsWith("unrelated-"))
                .collect(Collectors.toMap(MetricFilter::filterName, f -> f));
        update(template("Retain", ""), "renamed", "WARN", "2", "UPDATE_COMPLETE");
        assertIdentity("renamed");
        assertThat(filters()).hasSize(100).noneMatch(f -> "errors".equals(f.filterName()));
        assertUnrelated(unrelated);
        assertSequence(List.of("errors:UPDATE_IN_PROGRESS", "errors:DELETE_IN_PROGRESS", "errors:DELETE_COMPLETE",
                "renamed:CREATE_IN_PROGRESS", "renamed:CREATE_COMPLETE", "renamed:UPDATE_COMPLETE"));
        assertThat(newEvents()).noneMatch(e -> "DELETE_SKIPPED".equals(e.resourceStatusAsString()));
        assertThat(newEvents()).anyMatch(e -> "DELETE_IN_PROGRESS".equals(e.resourceStatusAsString())
                && ("Requested update requires the replacement of the existing resource; "
                + "deleting existing resource, then creating a new one.").equals(e.resourceStatusReason()));
        deleteStack();
        assertThat(filters()).hasSize(99);
        assertUnrelated(unrelated);
    }

    @Test
    void failedReplacementRestoresActualDefinitionTemplateAndNameOnlyIdentity() throws InterruptedException {
        String template = template("", "");
        create(template, "old", "WARN", "2");
        MetricFilter before = filter("old");
        update(template, "invalid", "{", "9", "UPDATE_ROLLBACK_COMPLETE");
        assertIdentity("old");
        assertThat(filters()).hasSize(1);
        MetricFilter after = filter("old");
        assertThat(after.toBuilder().creationTime(before.creationTime()).build()).isEqualTo(before);
        assertThat(after.creationTime()).isGreaterThan(before.creationTime());
        assertThat(cfn.getTemplate(r -> r.stackName(stack)).templateBody()).isEqualTo(template);
        assertSequence(List.of("old:UPDATE_IN_PROGRESS", "old:DELETE_IN_PROGRESS", "old:DELETE_COMPLETE",
                "invalid:CREATE_IN_PROGRESS", "invalid:CREATE_FAILED", "invalid:UPDATE_FAILED",
                "invalid:UPDATE_IN_PROGRESS", "invalid:DELETE_IN_PROGRESS", "invalid:DELETE_COMPLETE",
                "invalid:CREATE_IN_PROGRESS", "old:CREATE_COMPLETE", "old:UPDATE_COMPLETE"));
        assertSample("WARN", 2, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laterDependentFailureRestoresTheFilterThatActuallyMutated(boolean replacement) throws InterruptedException {
        String template = template("", "");
        create(template, "old", "WARN", "2");
        MetricFilter before = filter("old");
        String target = replacement ? "new" : "old";
        update(template("", """
                ,"BadSecret":{"Type":"AWS::SecretsManager::Secret","DependsOn":"Filter","Properties":{
                  "Name":"%s-bad","SecretString":"explicit","GenerateSecretString":{"PasswordLength":32}}}
                """.formatted(stack)), target, "FATAL", "9", "UPDATE_ROLLBACK_COMPLETE");
        assertIdentity("old");
        MetricFilter after = filter("old");
        assertThat(after.toBuilder().creationTime(before.creationTime()).build()).isEqualTo(before);
        assertThat(filters()).hasSize(1);
        List<StackEvent> events = newEvents();
        int changed = indexOf(events, "Filter", "UPDATE_COMPLETE", target);
        int failed = indexOf(events, "BadSecret", "UPDATE_FAILED", null);
        assertThat(changed).as("filter update completed before dependent failure").isGreaterThanOrEqualTo(0);
        assertThat(failed).isGreaterThan(changed);
        assertSample("WARN", 2, null);
    }

    @Test
    void generatedNameStaysStableWhenPatternAndValueReallyChange() throws InterruptedException {
        String template = """
                {"Resources":{"Filter":{"Type":"AWS::Logs::MetricFilter","Properties":{
                  "LogGroupName":"%s","FilterPattern":"{ $.latency = * }",
                  "MetricTransformations":[{"MetricName":"Count","MetricNamespace":"%s","MetricValue":"$.latency",
                    "Dimensions":[{"Key":"Route","Value":"$.route"}],"Unit":"Count"}]}}},
                 "Outputs":{"FilterRef":{"Value":{"Ref":"Filter"}}}}
                """.formatted(group, stack);
        cfn.createStack(r -> r.stackName(stack).templateBody(template));
        awaitStatus("CREATE_COMPLETE");
        String name = output();
        assertThat(name).startsWith(stack + "-Filter-");
        captureEvents();
        cfn.updateStack(r -> r.stackName(stack).templateBody(template.replace("$.latency = *", "$.latency > 10")
                .replace("\"MetricValue\":\"$.latency\"", "\"MetricValue\":\"3\"")));
        awaitStatus("UPDATE_COMPLETE");
        assertIdentity(name);
        assertThat(filters()).hasSize(1);
        assertThat(filter(name).filterPattern()).isEqualTo("{ $.latency > 10 }");
        assertThat(filter(name).metricTransformations().get(0).metricValue()).isEqualTo("3");
        assertSample("{\"latency\":20,\"route\":\"/orders\"}", 3, "/orders");
    }

    @Test
    void collidingReplacementNeverAdoptsOrRollbackDeletesUnrelatedFilter() throws InterruptedException {
        create(template("", ""), "old", "WARN", "2");
        logs.putMetricFilter(r -> r.logGroupName(group).filterName("taken").filterPattern("UNRELATED")
                .metricTransformations(MetricTransformation.builder().metricName("Other")
                        .metricNamespace(stack + "/unrelated").metricValue("7").build()));
        MetricFilter unrelated = filter("taken");
        update(template("", ""), "taken", "FATAL", "9", "UPDATE_ROLLBACK_COMPLETE");
        assertIdentity("old");
        assertThat(filter("taken")).isEqualTo(unrelated);
        assertThat(filters()).hasSize(2);
        assertSample("WARN", 2, null);
        deleteStack();
        assertThat(filters()).containsExactly(unrelated);
    }

    private void create(String template, String name, String pattern, String value) throws InterruptedException {
        cfn.createStack(r -> r.stackName(stack).templateBody(template).parameters(parameters(name, pattern, value)));
        awaitStatus("CREATE_COMPLETE");
    }

    private void update(String template, String name, String pattern, String value, String status)
            throws InterruptedException {
        captureEvents();
        cfn.updateStack(r -> r.stackName(stack).templateBody(template).parameters(parameters(name, pattern, value)));
        awaitStatus(status);
    }

    private String template(String retain, String extra) {
        return """
                {"Parameters":{"FilterName":{"Type":"String"},"Pattern":{"Type":"String"},"Value":{"Type":"String"}},
                 "Resources":{"Filter":{"Type":"AWS::Logs::MetricFilter",%s"Properties":{
                   "LogGroupName":"%s","FilterName":{"Ref":"FilterName"},"FilterPattern":{"Ref":"Pattern"},
                   "MetricTransformations":[{"MetricName":"Count","MetricNamespace":"%s","MetricValue":{"Ref":"Value"},
                     "DefaultValue":7,"Unit":"Count"}],"ApplyOnTransformedLogs":false}}%s},
                 "Outputs":{"FilterRef":{"Value":{"Ref":"Filter"}}}}
                """.formatted(retain.isEmpty() ? "" : "\"UpdateReplacePolicy\": \"" + retain + "\",", group, stack, extra);
    }

    private static List<Parameter> parameters(String name, String pattern, String value) {
        return List.of(Parameter.builder().parameterKey("FilterName").parameterValue(name).build(),
                Parameter.builder().parameterKey("Pattern").parameterValue(pattern).build(),
                Parameter.builder().parameterKey("Value").parameterValue(value).build());
    }

    private List<MetricFilter> filters() {
        return logs.describeMetricFiltersPaginator(r -> r.logGroupName(group)).metricFilters().stream().toList();
    }

    private MetricFilter filter(String name) {
        return filters().stream().filter(f -> name.equals(f.filterName())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing backing filter " + name + ": " + filters()));
    }

    private void assertUnrelated(Map<String, MetricFilter> expected) {
        assertThat(filters().stream().filter(f -> f.filterName().startsWith("unrelated-"))
                .collect(Collectors.toMap(MetricFilter::filterName, f -> f))).isEqualTo(expected);
    }

    private String output() {
        return cfn.describeStacks(r -> r.stackName(stack)).stacks().get(0).outputs().stream()
                .filter(o -> "FilterRef".equals(o.outputKey())).map(Output::outputValue).findFirst().orElseThrow();
    }

    private void assertIdentity(String name) {
        assertThat(output()).isEqualTo(name);
        assertThat(cfn.describeStackResource(r -> r.stackName(stack).logicalResourceId("Filter"))
                .stackResourceDetail().physicalResourceId()).isEqualTo(name);
    }

    private void captureEvents() {
        priorEvents = cfn.describeStackEvents(r -> r.stackName(stack)).stackEvents().stream()
                .map(StackEvent::eventId).collect(Collectors.toSet());
    }

    private List<StackEvent> newEvents() {
        List<StackEvent> events = new ArrayList<>(cfn.describeStackEvents(r -> r.stackName(stack)).stackEvents());
        Collections.reverse(events);
        return events.stream().filter(e -> !priorEvents.contains(e.eventId())).toList();
    }

    private void assertSequence(List<String> expected) {
        List<StackEvent> events = newEvents();
        assertThat(events).extracting(StackEvent::eventId).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(events.stream().filter(e -> "Filter".equals(e.logicalResourceId()))
                .map(e -> e.physicalResourceId() + ":" + e.resourceStatusAsString()).toList()).isEqualTo(expected);
    }

    private static int indexOf(List<StackEvent> events, String logical, String status, String physical) {
        for (int i = 0; i < events.size(); i++) {
            StackEvent e = events.get(i);
            if (logical.equals(e.logicalResourceId()) && status.equals(e.resourceStatusAsString())
                    && (physical == null || physical.equals(e.physicalResourceId()))) {
                return i;
            }
        }
        return -1;
    }

    private void awaitStatus(String status) throws InterruptedException {
        await(() -> status.equals(cfn.describeStacks(r -> r.stackName(stack)).stacks().get(0).stackStatusAsString())
                && newEvents().stream().anyMatch(e -> stack.equals(e.logicalResourceId())
                && status.equals(e.resourceStatusAsString())), "new operation " + status);
    }

    private void await(BooleanSupplier condition, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(stack + " timed out waiting for " + expected + "; events: " + newEvents());
    }

    private void assertSample(String message, double expected, String route) throws InterruptedException {
        Instant timestamp = Instant.now();
        logs.putLogEvents(r -> r.logGroupName(group).logStreamName("events")
                .logEvents(InputLogEvent.builder().timestamp(timestamp.toEpochMilli()).message(message).build()));
        await(() -> {
            List<Datapoint> data = metrics.getMetricStatistics(r -> {
                r.namespace(stack).metricName("Count").period(60).startTime(timestamp.minusSeconds(60))
                        .endTime(timestamp.plusSeconds(60)).statistics(Statistic.SUM, Statistic.SAMPLE_COUNT);
                if (route != null) {
                    r.dimensions(Dimension.builder()
                            .name("Route").value(route).build());
                }
            }).datapoints();
            return data.stream().mapToDouble(Datapoint::sum).sum() == expected
                    && data.stream().mapToDouble(Datapoint::sampleCount).sum() == 1;
        }, "one published sample of " + expected);
    }

    private void deleteStack() throws InterruptedException {
        try {
            cfn.deleteStack(r -> r.stackName(stack));
            await(() -> {
                try {
                    return "DELETE_COMPLETE".equals(cfn.describeStacks(r -> r.stackName(stack)).stacks().get(0)
                            .stackStatusAsString());
                } catch (CloudFormationException e) {
                    if ("ValidationError".equals(e.awsErrorDetails().errorCode()) && e.getMessage().contains("does not exist")) {
                        return true;
                    }
                    throw e;
                }
            }, "stack deletion");
        } catch (CloudFormationException e) {
            if (!"ValidationError".equals(e.awsErrorDetails().errorCode()) || !e.getMessage().contains("does not exist")) {
                throw e;
            }
        }
    }
}
