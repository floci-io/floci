package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.ArchiveState;
import software.amazon.awssdk.services.eventbridge.model.DescribeArchiveResponse;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation AWS::Events::Archive")
class CloudFormationEventsArchiveTest {

    private static final String PATTERN = "{\"source\":[\"app.orders\"]}";

    private static CloudFormationClient cfn;
    private static EventBridgeClient eventBridge;
    private String stackName;
    private boolean stackCreated;
    private final List<String> buses = new ArrayList<>();

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        eventBridge = TestFixtures.eventBridgeClient();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-archive");
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        try {
            deleteStack();
        } finally {
            for (String bus : buses) {
                eventBridge.deleteEventBus(r -> r.name(bus));
            }
        }
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        eventBridge.close();
    }

    @Test
    void createsArchiveThatReadsBackThroughDescribeArchiveAndDeletesItWithTheStack()
            throws InterruptedException {
        String bus = createBus();
        String name = TestFixtures.uniqueName("orders");

        createStack(template(name, true), parameters(bus, "7"));

        assertThat(output("ArchiveRef")).as("Ref is the archive name").isEqualTo(name);
        DescribeArchiveResponse archive = eventBridge.describeArchive(r -> r.archiveName(name));
        assertThat(output("ArchiveArn")).as("GetAtt Arn is the archive ARN").isEqualTo(archive.archiveArn());
        assertThat(archive.eventSourceArn()).isEqualTo(bus);
        assertThat(archive.eventPattern()).isEqualTo(PATTERN);
        assertThat(archive.retentionDays()).isEqualTo(7);
        assertThat(archive.description()).isEqualTo("orders archive");
        assertThat(archive.state()).isEqualTo(ArchiveState.ENABLED);

        deleteStack();
        assertThatThrownBy(() -> eventBridge.describeArchive(r -> r.archiveName(name)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void inPlaceUpdateKeepsTheArchiveAndADescriptionRemovedFromTheTemplate() throws InterruptedException {
        String bus = createBus();
        String name = TestFixtures.uniqueName("orders");
        createStack(template(name, true), parameters(bus, "7"));
        String arn = output("ArchiveArn");

        cfn.updateStack(r -> r.stackName(stackName).templateBody(template(name, false))
                .parameters(parameters(bus, "30")));
        awaitStatus("UPDATE_COMPLETE");

        assertThat(output("ArchiveRef")).isEqualTo(name);
        assertThat(output("ArchiveArn")).isEqualTo(arn);
        DescribeArchiveResponse archive = eventBridge.describeArchive(r -> r.archiveName(name));
        assertThat(archive.retentionDays()).isEqualTo(30);
        assertThat(archive.description())
                .as("a property removed from the template keeps its value")
                .isEqualTo("orders archive");
        assertThat(archive.eventPattern()).isEqualTo(PATTERN);
    }

    @Test
    void changingTheSourceReplacesAGeneratedArchive() throws InterruptedException {
        String first = createBus();
        String second = createBus();
        createStack(template(null, false), parameters(first, "7"));
        String original = output("ArchiveRef");
        assertThat(original).contains("Archive-").hasSizeLessThanOrEqualTo(48);

        cfn.updateStack(r -> r.stackName(stackName).templateBody(template(null, false))
                .parameters(parameters(second, "7")));
        awaitStatus("UPDATE_COMPLETE");

        String replacement = output("ArchiveRef");
        assertThat(replacement).contains("Archive-").isNotEqualTo(original).hasSizeLessThanOrEqualTo(48);
        assertThat(eventBridge.describeArchive(r -> r.archiveName(replacement)).eventSourceArn())
                .isEqualTo(second);
        assertThatThrownBy(() -> eventBridge.describeArchive(r -> r.archiveName(original)))
                .as("the displaced archive is deleted once the update commits")
                .isInstanceOf(ResourceNotFoundException.class);

        deleteStack();
        assertThatThrownBy(() -> eventBridge.describeArchive(r -> r.archiveName(replacement)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void declaringTheGeneratedNameExplicitlyIsRefusedAsACustomNamedReplacement() throws InterruptedException {
        String bus = createBus();
        createStack(template(null, false), parameters(bus, "7"));
        String generated = cfn.describeStackResource(r -> r.stackName(stackName).logicalResourceId("Archive"))
                .stackResourceDetail().physicalResourceId();

        cfn.updateStack(r -> r.stackName(stackName).templateBody(template(generated, false))
                .parameters(parameters(bus, "7")));
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");

        String reason = "CloudFormation cannot update a stack when a custom-named resource requires replacing. "
                + "Rename " + generated + " and update the stack again.";
        assertThat(cfn.describeStackEvents(r -> r.stackName(stackName)).stackEvents())
                .anyMatch(e -> "Archive".equals(e.logicalResourceId())
                        && "UPDATE_FAILED".equals(e.resourceStatusAsString())
                        && reason.equals(e.resourceStatusReason()));
        assertThat(output("ArchiveRef")).isEqualTo(generated);
        DescribeArchiveResponse archive = eventBridge.describeArchive(r -> r.archiveName(generated));
        assertThat(archive.eventSourceArn()).isEqualTo(bus);
        assertThat(archive.retentionDays()).isEqualTo(7);
    }

    @Test
    void failedUpdateRestoresTheInPlaceChangeMadeBeforeALaterResourceFailed() throws InterruptedException {
        String name = TestFixtures.uniqueName("orders");
        String broken = TestFixtures.uniqueName("broken");
        createStack(defaultBusTemplate(name, "orders archive", 7, null), List.of());

        cfn.updateStack(r -> r.stackName(stackName)
                .templateBody(defaultBusTemplate(name, "changed archive", 30, broken)));
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");

        DescribeArchiveResponse archive = eventBridge.describeArchive(r -> r.archiveName(name));
        assertThat(archive.description()).isEqualTo("orders archive");
        assertThat(archive.retentionDays()).isEqualTo(7);
        assertThatThrownBy(() -> eventBridge.describeArchive(r -> r.archiveName(broken)))
                .as("the archive on a missing bus is never created")
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createArchiveAndUpdateArchiveRejectOutOfRangeFieldsBeforeAnyOtherCheck() {
        String missingBus = eventBridge.describeEventBus(r -> r.name("default")).arn()
                .replace("event-bus/default", "event-bus/" + TestFixtures.uniqueName("compat-archive-missing"));
        String name = TestFixtures.uniqueName("compat-archive-invalid");
        String description = "d".repeat(513);
        String pattern = "{\"source\":[\"" + "a".repeat(4082) + "\"]}";

        assertThatThrownBy(() -> eventBridge.createArchive(r -> r.archiveName(name).eventSourceArn(missingBus)
                .description(description).eventPattern(pattern).retentionDays(-1)))
                .as("validation runs before the source check")
                .isInstanceOfSatisfying(EventBridgeException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationException");
                    assertThat(error.awsErrorDetails().errorMessage())
                            .startsWith("3 validation errors detected: Value '-1' at 'retentionDays'")
                            .contains("at 'description' failed to satisfy constraint: "
                                            + "Member must have length less than or equal to 512",
                                    "at 'eventPattern' failed to satisfy constraint: "
                                            + "Member must have length less than or equal to 4096");
                });
        assertThatThrownBy(() -> eventBridge.updateArchive(r -> r.archiveName(name).retentionDays(-1)))
                .as("validation runs before the archive is looked up")
                .isInstanceOfSatisfying(EventBridgeException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationException");
                    assertThat(error.awsErrorDetails().errorMessage()).isEqualTo("1 validation error detected: "
                            + "Value '-1' at 'retentionDays' failed to satisfy constraint: "
                            + "Member must have value greater than or equal to 0");
                });
    }

    /**
     * An archive on the account's default bus and, when {@code brokenName} is given, a second one
     * that depends on it and names a bus that does not exist, so it fails after the first changed.
     */
    private static String defaultBusTemplate(String name, String description, int retention, String brokenName) {
        String broken = brokenName == null ? "" : """
                ,
                    "Broken": {
                      "Type": "AWS::Events::Archive",
                      "DependsOn": "Archive",
                      "Properties": {
                        "ArchiveName": "%s",
                        "SourceArn": {"Fn::Sub": "arn:${AWS::Partition}:events:${AWS::Region}:${AWS::AccountId}:event-bus/%s"}
                      }
                    }""".formatted(brokenName, brokenName + "-missing-bus");
        return """
                {
                  "Resources": {
                    "Archive": {
                      "Type": "AWS::Events::Archive",
                      "Properties": {
                        "ArchiveName": "%s",
                        "Description": "%s",
                        "SourceArn": {"Fn::Sub": "arn:${AWS::Partition}:events:${AWS::Region}:${AWS::AccountId}:event-bus/default"},
                        "RetentionDays": %d
                      }
                    }%s
                  }
                }
                """.formatted(name, description, retention, broken);
    }

    private static String template(String archiveName, boolean withDescription) {
        String name = archiveName == null ? "" : "\"ArchiveName\": \"" + archiveName + "\",";
        String description = withDescription ? "\"Description\": \"orders archive\"," : "";
        return """
                {
                  "Parameters": {
                    "Source": {"Type": "String"},
                    "Retention": {"Type": "Number"}
                  },
                  "Resources": {
                    "Archive": {
                      "Type": "AWS::Events::Archive",
                      "Properties": {
                        %s
                        %s
                        "SourceArn": {"Ref": "Source"},
                        "EventPattern": {"source": ["app.orders"]},
                        "RetentionDays": {"Ref": "Retention"}
                      }
                    }
                  },
                  "Outputs": {
                    "ArchiveRef": {"Value": {"Ref": "Archive"}},
                    "ArchiveArn": {"Value": {"Fn::GetAtt": ["Archive", "Arn"]}}
                  }
                }
                """.formatted(name, description);
    }

    private static List<Parameter> parameters(String source, String retention) {
        return List.of(
                Parameter.builder().parameterKey("Source").parameterValue(source).build(),
                Parameter.builder().parameterKey("Retention").parameterValue(retention).build());
    }

    private String createBus() {
        String bus = TestFixtures.uniqueName("compat-archive-bus");
        String arn = eventBridge.createEventBus(r -> r.name(bus)).eventBusArn();
        buses.add(bus);
        return arn;
    }

    private void createStack(String template, List<Parameter> parameters) throws InterruptedException {
        cfn.createStack(r -> {
            r.stackName(stackName).templateBody(template);
            if (!parameters.isEmpty()) {
                r.parameters(parameters);
            }
        });
        stackCreated = true;
        awaitStatus("CREATE_COMPLETE");
    }

    private String output(String key) {
        return cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0).outputs().stream()
                .filter(o -> key.equals(o.outputKey())).map(Output::outputValue)
                .findFirst().orElseThrow();
    }

    /** Waits for {@code expected}, failing as soon as the stack settles on any other status. */
    private void awaitStatus(String expected) throws InterruptedException {
        await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (!expected.equals(status) && !status.endsWith("_IN_PROGRESS")) {
                throw new AssertionError(stackName + " reached " + status + ": " + stack.stackStatusReason());
            }
            return expected.equals(status);
        }, expected);
    }

    private void deleteStack() throws InterruptedException {
        if (!stackCreated) {
            return;
        }
        cfn.deleteStack(r -> r.stackName(stackName));
        await(() -> {
            try {
                List<Stack> stacks = cfn.describeStacks(r -> r.stackName(stackName)).stacks();
                if (stacks.isEmpty()) {
                    return true;
                }
                Stack stack = stacks.get(0);
                if ("DELETE_FAILED".equals(stack.stackStatusAsString())) {
                    throw new AssertionError(stackName + " deletion failed: " + stack.stackStatusReason());
                }
                return "DELETE_COMPLETE".equals(stack.stackStatusAsString());
            } catch (CloudFormationException e) {
                if ("ValidationError".equals(e.awsErrorDetails().errorCode())
                        && e.getMessage().contains("does not exist")) {
                    return true;
                }
                throw e;
            }
        }, "stack deletion");
        stackCreated = false;
    }

    private void await(BooleanSupplier condition, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(stackName + " timed out waiting for " + expected);
    }
}
