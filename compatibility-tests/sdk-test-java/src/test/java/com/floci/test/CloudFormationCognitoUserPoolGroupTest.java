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
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GroupType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ResourceNotFoundException;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives an {@code AWS::Cognito::UserPoolGroup} through in-place and replacing updates and reads
 * the groups back through the Cognito SDK. The group's create-only properties are
 * {@code UserPoolId} and {@code GroupName}; {@code Ref} and the physical id are the bare group name.
 * The pools are created outside the stack, so the groups left in them stay observable and a
 * rolled-back update touches only the groups.
 */
@DisplayName("CloudFormation AWS::Cognito::UserPoolGroup")
class CloudFormationCognitoUserPoolGroupTest {

    private static final long TIMEOUT_NANOS = 300_000_000_000L;

    private static final String TEMPLATE = """
            {
              "Parameters": {
                "GroupName": {"Type": "String", "Default": ""},
                "UserPoolId": {"Type": "String"},
                "Description": {"Type": "String"},
                "Precedence": {"Type": "Number"},
                "Fail": {"Type": "String", "Default": "false"}
              },
              "Conditions": {
                "Named": {"Fn::Not": [{"Fn::Equals": [{"Ref": "GroupName"}, ""]}]},
                "Fail": {"Fn::Equals": [{"Ref": "Fail"}, "true"]}
              },
              "Resources": {
                "Group": {
                  "Type": "AWS::Cognito::UserPoolGroup",
                  "Properties": {
                    "UserPoolId": {"Ref": "UserPoolId"},
                    "GroupName": {"Fn::If": ["Named", {"Ref": "GroupName"}, {"Ref": "AWS::NoValue"}]},
                    "Description": {"Ref": "Description"},
                    "Precedence": {"Ref": "Precedence"}
                  }
                },
                "Duplicate": {
                  "Type": "AWS::Cognito::UserPoolGroup",
                  "Condition": "Fail",
                  "DependsOn": "Group",
                  "Properties": {"UserPoolId": {"Ref": "UserPoolId"}, "GroupName": {"Ref": "GroupName"}}
                }
              },
              "Outputs": {
                "GroupRef": {"Value": {"Ref": "Group"}}
              }
            }
            """;

    private static CloudFormationClient cfn;
    private static CognitoIdentityProviderClient cognito;

    private String stackName;
    private String stackId;
    private String p1;
    private String p2;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        cognito = TestFixtures.cognitoClient();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-cug");
        p1 = cognito.createUserPool(r -> r.poolName(stackName + "-p1")).userPool().id();
        p2 = cognito.createUserPool(r -> r.poolName(stackName + "-p2")).userPool().id();
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        try {
            deleteStack();
        } finally {
            for (String pool : new String[] {p1, p2}) {
                if (pool == null) {
                    continue;
                }
                try {
                    cognito.deleteUserPool(r -> r.userPoolId(pool));
                } catch (ResourceNotFoundException expected) {
                    // The pool never finished creating or was already removed.
                }
            }
            p1 = null;
            p2 = null;
        }
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        cognito.close();
    }

    @Test
    @DisplayName("Description and Precedence update the named group in place, and the stack delete removes it")
    void inPlaceUpdateKeepsTheNamedGroupAndStackDeletionRemovesIt() throws InterruptedException {
        String name = stackName + "-n1";
        createStack(parameters(name, p1, "d1", 1, false));
        assertIdentity(name);
        GroupType created = group(p1, name);
        assertGroup(created, p1, "d1", 1);
        assertThat(groupNames(p1)).containsExactly(name);
        assertThat(groupNames(p2)).isEmpty();

        updateStack(parameters(name, p1, "d2", 2, false), "UPDATE_COMPLETE");
        assertIdentity(name);
        GroupType updated = group(p1, name);
        assertGroup(updated, p1, "d2", 2);
        assertThat(updated.creationDate()).as("an in-place update keeps the group").isEqualTo(created.creationDate());
        assertThat(groupNames(p1)).containsExactly(name);
        assertThat(groupNames(p2)).isEmpty();

        deleteStack();
        assertThat(groupNames(p1)).as("the stack delete removes the group from the retained pool").isEmpty();
        assertThat(groupNames(p2)).isEmpty();
    }

    @Test
    @DisplayName("Renaming the group replaces it and deletes the group under the old name")
    void renamingTheGroupReplacesItAndDeletesTheOldName() throws InterruptedException {
        String original = stackName + "-n1";
        String renamed = stackName + "-n2";
        createStack(parameters(original, p1, "d1", 1, false));
        assertIdentity(original);

        updateStack(parameters(renamed, p1, "d1", 1, false), "UPDATE_COMPLETE");
        assertIdentity(renamed);
        assertGroup(group(p1, renamed), p1, "d1", 1);
        assertGroupNotFound(p1, original);
        assertThat(groupNames(p1)).containsExactly(renamed);
        assertThat(groupNames(p2)).isEmpty();

        deleteStack();
        assertThat(groupNames(p1)).isEmpty();
        assertThat(groupNames(p2)).isEmpty();
    }

    @Test
    @DisplayName("Moving a named group to another pool creates it there and leaves the old group behind")
    void movingANamedGroupToAnotherPoolCreatesItThereAndLeavesTheOldGroupBehind() throws InterruptedException {
        String name = stackName + "-n1";
        createStack(parameters(name, p1, "d1", 1, false));
        assertIdentity(name);
        GroupType original = group(p1, name);

        updateStack(parameters(name, p2, "d1", 1, false), "UPDATE_COMPLETE");
        assertIdentity(name);
        assertGroup(group(p2, name), p2, "d1", 1);
        assertThat(groupNames(p2)).containsExactly(name);
        assertThat(groupNames(p1))
                .as("AWS replaces the group but skips deleting the old one: its physical id, the bare"
                        + " group name, did not change")
                .containsExactly(name);
        assertThat(group(p1, name).creationDate()).isEqualTo(original.creationDate());

        deleteStack();
        assertThat(groupNames(p2)).as("the stack delete removes the group it owns").isEmpty();
        assertThat(groupNames(p1)).as("the group left in the old pool is no longer owned by the stack")
                .containsExactly(name);
    }

    @Test
    @DisplayName("Declaring a generated group's name explicitly is refused as a custom-named replacement and rolls back")
    void declaringAGeneratedGroupNameExplicitlyIsRefusedAndRollsBack() throws InterruptedException {
        createStack(parameters("", p1, "d1", 1, false));
        String generated = output("GroupRef");
        assertGeneratedName(generated);
        assertIdentity(generated);
        GroupType created = group(p1, generated);

        // GroupName is create-only, so declaring it requires a replacement, but the replacement
        // would claim the same pool and name: CloudFormation refuses it before creating anything.
        updateStack(parameters(generated, p1, "d1", 1, false), "UPDATE_ROLLBACK_COMPLETE");
        List<String> failures = groupEvents().stream()
                .filter(event -> "UPDATE_FAILED".equals(event.resourceStatusAsString()))
                .map(StackEvent::resourceStatusReason)
                .toList();
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).contains("CloudFormation cannot update a stack when a custom-named resource"
                + " requires replacing. Rename " + generated + " and update the stack again.");
        assertIdentity(generated);
        GroupType kept = group(p1, generated);
        assertGroup(kept, p1, "d1", 1);
        assertThat(kept.creationDate()).isEqualTo(created.creationDate());
        assertThat(groupNames(p1)).containsExactly(generated);
        assertThat(groupNames(p2)).isEmpty();

        deleteStack();
        assertThat(groupNames(p1)).isEmpty();
        assertThat(groupNames(p2)).isEmpty();
    }

    @Test
    @DisplayName("Removing GroupName replaces the named group with a generated one and deletes the named group")
    void removingTheGroupNameReplacesTheGroupWithAGeneratedName() throws InterruptedException {
        String name = stackName + "-n1";
        createStack(parameters(name, p1, "d1", 1, false));
        assertIdentity(name);

        updateStack(parameters("", p1, "d1", 1, false), "UPDATE_COMPLETE");
        String generated = output("GroupRef");
        assertGeneratedName(generated);
        assertThat(generated).isNotEqualTo(name);
        assertIdentity(generated);
        assertGroup(group(p1, generated), p1, "d1", 1);
        assertGroupNotFound(p1, name);
        assertThat(groupNames(p1)).containsExactly(generated);
        assertThat(groupNames(p2)).isEmpty();

        deleteStack();
        assertThat(groupNames(p1)).isEmpty();
        assertThat(groupNames(p2)).isEmpty();
    }

    @Test
    @DisplayName("Moving a generated group to another pool replaces it under a new generated name")
    void movingAGeneratedGroupToAnotherPoolReplacesItUnderANewGeneratedName() throws InterruptedException {
        createStack(parameters("", p1, "d1", 1, false));
        String original = output("GroupRef");
        assertGeneratedName(original);
        assertIdentity(original);
        assertGroup(group(p1, original), p1, "d1", 1);

        updateStack(parameters("", p2, "d1", 1, false), "UPDATE_COMPLETE");
        String replacement = output("GroupRef");
        assertGeneratedName(replacement);
        assertThat(replacement).as("the replacement gets a freshly generated name").isNotEqualTo(original);
        assertIdentity(replacement);
        assertGroup(group(p2, replacement), p2, "d1", 1);
        assertThat(groupNames(p2)).containsExactly(replacement);
        assertThat(groupNames(p1)).as("the generated group in the old pool is deleted").isEmpty();

        deleteStack();
        assertThat(groupNames(p1)).isEmpty();
        assertThat(groupNames(p2)).isEmpty();
    }

    @Test
    @DisplayName("A failed update after a rename deletes the new group and keeps the old one")
    void failedUpdateAfterARenameDeletesTheNewGroupAndKeepsTheOldOne() throws InterruptedException {
        String original = stackName + "-n1";
        String renamed = stackName + "-n2";
        createStack(parameters(original, p1, "d1", 1, false));
        assertIdentity(original);
        GroupType created = group(p1, original);

        // Duplicate depends on Group and claims the same pool and name, so it fails after the
        // replacement group exists and the update rolls back.
        updateStack(parameters(renamed, p1, "d1", 1, true), "UPDATE_ROLLBACK_COMPLETE");
        assertIdentity(original);
        GroupType kept = group(p1, original);
        assertGroup(kept, p1, "d1", 1);
        assertThat(kept.creationDate()).isEqualTo(created.creationDate());
        assertGroupNotFound(p1, renamed);
        assertThat(groupNames(p1)).containsExactly(original);
        assertThat(groupNames(p2)).isEmpty();

        deleteStack();
        assertThat(groupNames(p1)).isEmpty();
        assertThat(groupNames(p2)).isEmpty();
    }

    @Test
    @DisplayName("A failed update after moving a named group keeps the old pool's group and leaves the new one behind")
    void failedUpdateAfterMovingANamedGroupKeepsTheOldPoolGroupAndLeavesTheNewOneBehind()
            throws InterruptedException {
        String name = stackName + "-n1";
        createStack(parameters(name, p1, "d1", 1, false));
        assertIdentity(name);
        GroupType created = group(p1, name);

        // Duplicate depends on Group and claims the new pool and the same name, so it fails after
        // the group exists in the new pool and the update rolls back.
        updateStack(parameters(name, p2, "d1", 1, true), "UPDATE_ROLLBACK_COMPLETE");
        assertIdentity(name);
        GroupType kept = group(p1, name);
        assertGroup(kept, p1, "d1", 1);
        assertThat(kept.creationDate()).isEqualTo(created.creationDate());
        assertThat(groupNames(p1)).containsExactly(name);
        assertThat(groupNames(p2))
                .as("the rollback skips deleting the group the failed update created: its physical id,"
                        + " the bare group name, did not change")
                .containsExactly(name);

        deleteStack();
        assertThat(groupNames(p1)).as("the stack delete removes the group it owns again").isEmpty();
        assertThat(groupNames(p2)).as("the group left in the new pool is not owned by the stack")
                .containsExactly(name);
    }

    private static List<Parameter> parameters(String groupName, String userPoolId, String description,
                                              int precedence, boolean fail) {
        return List.of(
                parameter("GroupName", groupName),
                parameter("UserPoolId", userPoolId),
                parameter("Description", description),
                parameter("Precedence", String.valueOf(precedence)),
                parameter("Fail", String.valueOf(fail)));
    }

    private static Parameter parameter(String key, String value) {
        return Parameter.builder().parameterKey(key).parameterValue(value).build();
    }

    private void createStack(List<Parameter> parameters) throws InterruptedException {
        stackId = cfn.createStack(r -> r.stackName(stackName).templateBody(TEMPLATE).parameters(parameters))
                .stackId();
        awaitStatus("CREATE_COMPLETE");
    }

    private void updateStack(List<Parameter> parameters, String expected) throws InterruptedException {
        cfn.updateStack(r -> r.stackName(stackName).templateBody(TEMPLATE).parameters(parameters));
        awaitStatus(expected);
    }

    private String output(String key) {
        return cfn.describeStacks(r -> r.stackName(stackId)).stacks().get(0).outputs().stream()
                .filter(o -> key.equals(o.outputKey())).map(Output::outputValue)
                .findFirst().orElseThrow();
    }

    private void assertIdentity(String groupName) {
        assertThat(output("GroupRef")).as("Ref is the group name").isEqualTo(groupName);
        assertThat(cfn.describeStackResource(r -> r.stackName(stackId).logicalResourceId("Group"))
                .stackResourceDetail().physicalResourceId())
                .as("the physical id is the bare group name").isEqualTo(groupName);
    }

    private static void assertGeneratedName(String name) {
        assertThat(name).isNotEmpty().contains("Group").hasSizeLessThanOrEqualTo(128);
    }

    private GroupType group(String pool, String name) {
        return cognito.getGroup(r -> r.userPoolId(pool).groupName(name)).group();
    }

    private static void assertGroup(GroupType group, String pool, String description, int precedence) {
        assertThat(group.userPoolId()).isEqualTo(pool);
        assertThat(group.description()).isEqualTo(description);
        assertThat(group.precedence()).isEqualTo(precedence);
    }

    private void assertGroupNotFound(String pool, String name) {
        assertThatThrownBy(() -> group(pool, name)).isInstanceOf(ResourceNotFoundException.class);
    }

    private List<String> groupNames(String pool) {
        return cognito.listGroupsPaginator(r -> r.userPoolId(pool)).groups().stream()
                .map(GroupType::groupName).sorted().toList();
    }

    private void awaitStatus(String expected) throws InterruptedException {
        await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackId)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (expected.equals(status)) {
                return true;
            }
            if (!status.endsWith("_IN_PROGRESS") && (status.contains("FAILED") || status.contains("ROLLBACK"))) {
                throw new AssertionError(stackName + " reached " + status + " instead of " + expected + ": "
                        + stack.stackStatusReason() + failedEvents());
            }
            return false;
        }, expected);
    }

    private List<StackEvent> groupEvents() {
        return cfn.describeStackEvents(r -> r.stackName(stackId)).stackEvents().stream()
                .filter(event -> "Group".equals(event.logicalResourceId()))
                .toList();
    }

    private String failedEvents() {
        return cfn.describeStackEvents(r -> r.stackName(stackId)).stackEvents().stream()
                .filter(event -> event.resourceStatusAsString().endsWith("_FAILED"))
                .map(event -> event.logicalResourceId() + " " + event.resourceStatusAsString() + ": "
                        + event.resourceStatusReason())
                .collect(Collectors.joining("; ", " [", "]"));
    }

    private void deleteStack() throws InterruptedException {
        if (stackId == null) {
            return;
        }
        cfn.deleteStack(r -> r.stackName(stackId));
        await(() -> {
            try {
                List<Stack> stacks = cfn.describeStacks(r -> r.stackName(stackId)).stacks();
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
        stackId = null;
    }

    private void await(BooleanSupplier condition, String expected) throws InterruptedException {
        long pollMillis = TestFixtures.isRealAws() ? 2_000 : 100;
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(pollMillis);
        }
        throw new AssertionError(stackName + " timed out waiting for " + expected);
    }
}
