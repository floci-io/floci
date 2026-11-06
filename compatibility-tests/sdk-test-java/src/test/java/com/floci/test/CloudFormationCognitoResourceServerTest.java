package com.floci.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ResourceNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ResourceServerScopeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ResourceServerType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Exercises resource servers through both CloudFormation and Cognito SDK clients. Pools live
 * outside the stack so replacement and rollback cleanup remain observable after stack deletion.
 */
@DisplayName("CloudFormation AWS::Cognito::UserPoolResourceServer")
class CloudFormationCognitoResourceServerTest {

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Explicit and resolved null Scopes are refused without touching the existing server")
    void nullScopesUpdatesPreserveTheExistingConfiguration(boolean conditional) throws Exception {
        String identifier = stackName + "-api";
        List<Parameter> original = parameters(identifier, p1, "Original API", "read", "Read access", true, false, false);
        createStack(original);
        String invalidTemplate = nullScopesTemplate(conditional);
        String path = conditional ? "/Resources/Server/Type/Scopes/Fn::If/1" : "/Resources/Server/Type/Scopes";

        // As on AWS, a template holding a null is refused when the update is requested.
        assertThatThrownBy(() -> cfn.updateStack(r -> r.stackName(stackName).templateBody(invalidTemplate)
                .parameters(parameters(identifier, p1, "Changed API", "write", "Write access", true, false, false))))
                .isInstanceOfSatisfying(CloudFormationException.class, e -> assertThat(e.awsErrorDetails().errorMessage())
                        .isEqualTo("[" + path + "] 'null' values are not allowed in templates"));
        awaitStatus("CREATE_COMPLETE");

        assertIdentity(identifier);
        assertServer(p1, identifier, "Original API", "read", "Read access");
        updateStack(parameters(identifier, p1, "Recovered API", "write", "Write access", true, false, false),
                "UPDATE_COMPLETE");
        assertServer(p1, identifier, "Recovered API", "write", "Write access");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("A missing old server does not block an Identifier or UserPoolId replacement")
    void replacingAnExternallyDeletedServerCreatesTheNewAddress(boolean changePool) throws InterruptedException {
        String original = stackName + "-api";
        String targetIdentifier = changePool ? original : stackName + "-replacement";
        String targetPool = changePool ? p2 : p1;
        createStack(parameters(original, p1, "Original API", "read", "Read access", true, false, false));
        cognito.deleteResourceServer(r -> r.userPoolId(p1).identifier(original));

        updateStack(parameters(targetIdentifier, targetPool, "Replacement API", "write", "Write access",
                true, false, false), "UPDATE_COMPLETE");

        assertIdentity(targetIdentifier);
        assertServer(targetPool, targetIdentifier, "Replacement API", "write", "Write access");
        assertServerNotFound(p1, original);
        deleteStack();
        assertServerNotFound(targetPool, targetIdentifier);
    }

    @Test
    @DisplayName("A same-address update still fails when its backing server was deleted")
    void updatingAMissingServerAtTheSameAddressStillFails() throws InterruptedException {
        String identifier = stackName + "-api";
        createStack(parameters(identifier, p1, "Original API", "read", "Read access", true, false, false));
        cognito.deleteResourceServer(r -> r.userPoolId(p1).identifier(identifier));

        updateStack(parameters(identifier, p1, "Changed API", "write", "Write access", true, false, false),
                "UPDATE_ROLLBACK_COMPLETE");

        assertIdentity(identifier);
        assertServerNotFound(p1, identifier);
    }

    @Test
    @DisplayName("Rollback of a missing-old replacement restores its address and deletes only the new server")
    void failedDependentResourceRollsBackAReplacementWhoseOldServerWasDeleted() throws InterruptedException {
        String identifier = stackName + "-api";
        createStack(parameters(identifier, p1, "Original API", "read", "Read access", true, false, false));
        cognito.deleteResourceServer(r -> r.userPoolId(p1).identifier(identifier));

        updateStack(parameters(identifier, p2, "Replacement API", "write", "Write access", true, false, true),
                "UPDATE_ROLLBACK_COMPLETE");

        assertDependentFailure();
        assertIdentity(identifier);
        assertServerNotFound(p1, identifier);
        assertServerNotFound(p2, identifier);
        assertStackDeletionClearsBothPools();
    }

    private static String nullScopesTemplate(boolean conditional) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode template = (ObjectNode) mapper.readTree(TEMPLATE);
        ObjectNode properties = (ObjectNode) template.path("Resources").path("Server").path("Properties");
        if (conditional) {
            properties.putObject("Scopes").putArray("Fn::If")
                    .add("IncludeScopes").addNull().add(mapper.createArrayNode());
        } else {
            properties.putNull("Scopes");
        }
        return template.toString();
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
        CloudFormationStackTestHelper.awaitStatus(cfn, stackId, stackName, expected);
    }

    private void deleteStack() throws InterruptedException {
        CloudFormationStackTestHelper.deleteStack(cfn, stackId, stackName);
        stackId = null;
    }
}
