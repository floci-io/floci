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
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ResourceNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ResourceServerScopeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ResourceServerType;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Exercises resource servers through both CloudFormation and Cognito SDK clients. Pools live
 * outside the stack so replacement and rollback cleanup remain observable after stack deletion.
 */
@DisplayName("CloudFormation AWS::Cognito::UserPoolResourceServer")
class CloudFormationCognitoResourceServerTest {

    private static final long TIMEOUT_NANOS = 300_000_000_000L;

    private static final String TEMPLATE = """
            {
              "Parameters": {
                "Identifier": {"Type": "String"},
                "UserPoolId": {"Type": "String"},
                "Name": {"Type": "String"},
                "ScopeName": {"Type": "String"},
                "ScopeDescription": {"Type": "String"},
                "IncludeScopes": {"Type": "String", "Default": "true"},
                "IncludeWrite": {"Type": "String", "Default": "false"},
                "Fail": {"Type": "String", "Default": "false"}
              },
              "Conditions": {
                "IncludeScopes": {"Fn::Equals": [{"Ref": "IncludeScopes"}, "true"]},
                "IncludeWrite": {"Fn::Equals": [{"Ref": "IncludeWrite"}, "true"]},
                "Fail": {"Fn::Equals": [{"Ref": "Fail"}, "true"]}
              },
              "Resources": {
                "Server": {
                  "Type": "AWS::Cognito::UserPoolResourceServer",
                  "Properties": {
                    "UserPoolId": {"Ref": "UserPoolId"},
                    "Identifier": {"Ref": "Identifier"},
                    "Name": {"Ref": "Name"},
                    "Scopes": {"Fn::If": ["IncludeScopes", [
                      {"ScopeName": {"Ref": "ScopeName"}, "ScopeDescription": {"Ref": "ScopeDescription"}},
                      {"Fn::If": ["IncludeWrite",
                        {"ScopeName": "write", "ScopeDescription": "Write access"},
                        {"Ref": "AWS::NoValue"}]}
                    ], {"Ref": "AWS::NoValue"}]}
                  }
                },
                "Duplicate": {
                  "Type": "AWS::Cognito::UserPoolResourceServer",
                  "Condition": "Fail",
                  "DependsOn": "Server",
                  "Properties": {
                    "UserPoolId": {"Ref": "UserPoolId"},
                    "Identifier": {"Ref": "Server"},
                    "Name": "Duplicate API"
                  }
                }
              },
              "Outputs": {
                "ServerRef": {"Value": {"Ref": "Server"}}
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
        stackName = TestFixtures.uniqueName("compat-cfn-crs");
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
    @DisplayName("Ref is Identifier, and Name and Scopes update in place before stack deletion")
    void nameAndScopesUpdateInPlaceAndStackDeletionRemovesTheServer() throws InterruptedException {
        String identifier = stackName + "-api";
        createStack(parameters(identifier, p1, "Original API", "read", "Read access", true, false, false));
        assertIdentity(identifier);
        assertServer(p1, identifier, "Original API", "read", "Read access");

        updateStack(parameters(identifier, p1, "Updated API", "browse", "Browse access", true, false, false),
                "UPDATE_COMPLETE");
        assertIdentity(identifier);
        assertServer(p1, identifier, "Updated API", "browse", "Browse access");
        assertThat(identifiers(p1)).containsExactly(identifier);
        assertThat(identifiers(p2)).isEmpty();

        deleteStack();
        assertServerNotFound(p1, identifier);
        assertThat(identifiers(p1)).isEmpty();
    }

    @Test
    @DisplayName("Changing only the Scopes condition omits, restores and clears the whole property")
    void changingOnlyTheConditionOmitsAndRestoresAllScopes() throws InterruptedException {
        String identifier = stackName + "-api";
        createStack(parameters(identifier, p1, "API", "read", "Read access", false, false, false));
        assertThat(server(p1, identifier).scopes()).isEmpty();

        updateStack(parameters(identifier, p1, "API", "read", "Read access", true, false, false), "UPDATE_COMPLETE");
        assertServer(p1, identifier, "API", "read", "Read access");
        updateStack(parameters(identifier, p1, "API", "read", "Read access", false, false, false), "UPDATE_COMPLETE");
        assertThat(server(p1, identifier).scopes()).isEmpty();
        assertIdentity(identifier);
        assertThat(identifiers(p1)).containsExactly(identifier);
    }

    @Test
    @DisplayName("AWS::NoValue omits one scope entry while preserving the other scope")
    void changingOnlyTheConditionOmitsAndRestoresOneScopeEntry() throws InterruptedException {
        String identifier = stackName + "-api";
        createStack(parameters(identifier, p1, "API", "read", "Read access", true, true, false));
        assertThat(server(p1, identifier).scopes())
                .extracting(ResourceServerScopeType::scopeName, ResourceServerScopeType::scopeDescription)
                .containsExactly(tuple("read", "Read access"), tuple("write", "Write access"));

        updateStack(parameters(identifier, p1, "API", "read", "Read access", true, false, false), "UPDATE_COMPLETE");
        assertServer(p1, identifier, "API", "read", "Read access");
        updateStack(parameters(identifier, p1, "API", "read", "Read access", true, true, false), "UPDATE_COMPLETE");
        assertThat(server(p1, identifier).scopes())
                .extracting(ResourceServerScopeType::scopeName, ResourceServerScopeType::scopeDescription)
                .containsExactly(tuple("read", "Read access"), tuple("write", "Write access"));
        assertIdentity(identifier);
    }

    @Test
    @DisplayName("Changing Identifier replaces the server and deletes the old identifier")
    void changingTheIdentifierReplacesTheServerAndDeletesTheOldOne() throws InterruptedException {
        String original = stackName + "-api";
        String replacement = stackName + "-new-api";
        createStack(parameters(original, p1, "API", "read", "Read access", true, false, false));

        updateStack(parameters(replacement, p1, "API", "read", "Read access", true, false, false), "UPDATE_COMPLETE");
        assertIdentity(replacement);
        assertServer(p1, replacement, "API", "read", "Read access");
        assertServerNotFound(p1, original);
        assertThat(identifiers(p1)).containsExactly(replacement);

        deleteStack();
        assertThat(identifiers(p1)).isEmpty();
    }

    @Test
    @DisplayName("Changing UserPoolId replaces the server even when Ref stays the same")
    void changingThePoolReplacesTheServerAndDeletesItFromTheOldPool() throws InterruptedException {
        String identifier = stackName + "-api";
        createStack(parameters(identifier, p1, "API", "read", "Read access", true, false, false));

        updateStack(parameters(identifier, p2, "API", "read", "Read access", true, false, false), "UPDATE_COMPLETE");
        assertIdentity(identifier);
        assertServer(p2, identifier, "API", "read", "Read access");
        assertServerNotFound(p1, identifier);
        assertThat(identifiers(p1)).isEmpty();
        assertThat(identifiers(p2)).containsExactly(identifier);

        deleteStack();
        assertThat(identifiers(p1)).isEmpty();
        assertThat(identifiers(p2)).isEmpty();
    }

    @Test
    @DisplayName("A failed dependent resource restores the prior Name and Scopes")
    void failedDependentResourceRestoresTheInPlaceConfiguration() throws InterruptedException {
        String identifier = stackName + "-api";
        createStack(parameters(identifier, p1, "Original API", "read", "Read access", true, false, false));

        updateStack(parameters(identifier, p1, "Attempted API", "browse", "Browse access", true, false, true),
                "UPDATE_ROLLBACK_COMPLETE");
        assertDependentFailure();
        assertIdentity(identifier);
        assertServer(p1, identifier, "Original API", "read", "Read access");
        assertThat(identifiers(p1)).containsExactly(identifier);
        assertThat(identifiers(p2)).isEmpty();
        assertStackDeletionClearsBothPools();
    }

    @Test
    @DisplayName("A failed dependent resource deletes a replacement identifier and restores the original")
    void failedDependentResourceRollsBackAnIdentifierReplacement() throws InterruptedException {
        String original = stackName + "-api";
        String replacement = stackName + "-new-api";
        createStack(parameters(original, p1, "Original API", "read", "Read access", true, false, false));

        updateStack(parameters(replacement, p1, "Attempted API", "browse", "Browse access", true, false, true),
                "UPDATE_ROLLBACK_COMPLETE");
        assertDependentFailure();
        assertIdentity(original);
        assertServer(p1, original, "Original API", "read", "Read access");
        assertServerNotFound(p1, replacement);
        assertThat(identifiers(p1)).containsExactly(original);
        assertThat(identifiers(p2)).isEmpty();
        assertStackDeletionClearsBothPools();
    }

    @Test
    @DisplayName("A failed dependent resource deletes the new pool's server and restores the prior pool")
    void failedDependentResourceRollsBackAPoolReplacement() throws InterruptedException {
        String identifier = stackName + "-api";
        createStack(parameters(identifier, p1, "Original API", "read", "Read access", true, false, false));

        updateStack(parameters(identifier, p2, "Attempted API", "browse", "Browse access", true, false, true),
                "UPDATE_ROLLBACK_COMPLETE");
        assertDependentFailure();
        assertIdentity(identifier);
        assertServer(p1, identifier, "Original API", "read", "Read access");
        assertServerNotFound(p2, identifier);
        assertThat(identifiers(p1)).containsExactly(identifier);
        assertThat(identifiers(p2)).isEmpty();
        assertStackDeletionClearsBothPools();
    }

    private static List<Parameter> parameters(String identifier, String userPoolId, String name, String scopeName,
                                              String scopeDescription, boolean includeScopes, boolean includeWrite,
                                              boolean fail) {
        return List.of(
                parameter("Identifier", identifier),
                parameter("UserPoolId", userPoolId),
                parameter("Name", name),
                parameter("ScopeName", scopeName),
                parameter("ScopeDescription", scopeDescription),
                parameter("IncludeScopes", String.valueOf(includeScopes)),
                parameter("IncludeWrite", String.valueOf(includeWrite)),
                parameter("Fail", String.valueOf(fail)));
    }

    private static Parameter parameter(String key, String value) {
        return Parameter.builder().parameterKey(key).parameterValue(value).build();
    }

    private void createStack(List<Parameter> parameters) throws InterruptedException {
        stackId = cfn.createStack(r -> r.stackName(stackName).templateBody(TEMPLATE).parameters(parameters)).stackId();
        awaitStatus("CREATE_COMPLETE");
    }

    private void updateStack(List<Parameter> parameters, String expected) throws InterruptedException {
        cfn.updateStack(r -> r.stackName(stackName).templateBody(TEMPLATE).parameters(parameters));
        awaitStatus(expected);
    }

    private void assertIdentity(String identifier) {
        assertThat(cfn.describeStacks(r -> r.stackName(stackId)).stacks().get(0).outputs())
                .filteredOn(o -> "ServerRef".equals(o.outputKey())).extracting(Output::outputValue)
                .as("Ref is the bare Identifier").containsExactly(identifier);
        assertThat(cfn.describeStackResource(r -> r.stackName(stackId).logicalResourceId("Server"))
                .stackResourceDetail().physicalResourceId())
                .as("the physical id is the bare Identifier").isEqualTo(identifier);
    }

    private ResourceServerType server(String pool, String identifier) {
        return cognito.describeResourceServer(r -> r.userPoolId(pool).identifier(identifier)).resourceServer();
    }

    private void assertServer(String pool, String identifier, String name, String scopeName, String scopeDescription) {
        ResourceServerType server = server(pool, identifier);
        assertThat(server.userPoolId()).isEqualTo(pool);
        assertThat(server.identifier()).isEqualTo(identifier);
        assertThat(server.name()).isEqualTo(name);
        assertThat(server.scopes()).extracting(ResourceServerScopeType::scopeName, ResourceServerScopeType::scopeDescription)
                .containsExactly(tuple(scopeName, scopeDescription));
    }

    private void assertServerNotFound(String pool, String identifier) {
        assertThatThrownBy(() -> server(pool, identifier)).isInstanceOf(ResourceNotFoundException.class);
    }

    private List<String> identifiers(String pool) {
        return cognito.listResourceServersPaginator(r -> r.userPoolId(pool)).resourceServers().stream()
                .map(ResourceServerType::identifier).sorted().toList();
    }

    private void assertStackDeletionClearsBothPools() throws InterruptedException {
        deleteStack();
        assertThat(identifiers(p1)).isEmpty();
        assertThat(identifiers(p2)).isEmpty();
    }

    private void assertDependentFailure() {
        assertThat(cfn.describeStackEvents(r -> r.stackName(stackId)).stackEvents())
                .anySatisfy(event -> {
                    assertThat(event.logicalResourceId()).isEqualTo("Duplicate");
                    assertThat(event.resourceStatusAsString()).isIn("CREATE_FAILED", "UPDATE_FAILED");
                    assertThat(event.resourceStatusReason()).contains("already exists");
                });
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
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        long pollMillis = TestFixtures.isRealAws() ? 2_000 : 100;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(pollMillis);
        }
        throw new AssertionError(stackName + " timed out waiting for " + expected);
    }
}
