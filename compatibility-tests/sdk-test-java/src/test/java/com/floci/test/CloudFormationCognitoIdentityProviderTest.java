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
import software.amazon.awssdk.services.cognitoidentityprovider.model.IdentityProviderType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ProviderDescription;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ResourceNotFoundException;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises identity providers through both CloudFormation and Cognito SDK clients. Pools live
 * outside the stack so replacement and rollback cleanup remain observable after stack deletion.
 */
@DisplayName("CloudFormation AWS::Cognito::UserPoolIdentityProvider")
class CloudFormationCognitoIdentityProviderTest {

    private static final long TIMEOUT_NANOS = 300_000_000_000L;

    private static final String TEMPLATE = """
            {
              "Parameters": {
                "UserPoolId": {"Type": "String"},
                "ProviderName": {"Type": "String"},
                "ClientId": {"Type": "String"},
                "Fail": {"Type": "String", "Default": "false"}
              },
              "Conditions": {
                "Fail": {"Fn::Equals": [{"Ref": "Fail"}, "true"]}
              },
              "Resources": {
                "Idp": {
                  "Type": "AWS::Cognito::UserPoolIdentityProvider",
                  "Properties": {
                    "UserPoolId": {"Ref": "UserPoolId"},
                    "ProviderName": {"Ref": "ProviderName"},
                    "ProviderType": "OIDC",
                    "ProviderDetails": {
                      "client_id": {"Ref": "ClientId"},
                      "client_secret": "example-secret",
                      "oidc_issuer": "https://issuer.example.com",
                      "authorize_scopes": "openid email",
                      "attributes_request_method": "GET"
                    },
                    "AttributeMapping": {"email": "email"}
                  }
                },
                "Duplicate": {
                  "Type": "AWS::Cognito::UserPoolIdentityProvider",
                  "Condition": "Fail",
                  "DependsOn": "Idp",
                  "Properties": {
                    "UserPoolId": {"Ref": "UserPoolId"},
                    "ProviderName": {"Ref": "Idp"},
                    "ProviderType": "OIDC",
                    "ProviderDetails": {
                      "client_id": "duplicate",
                      "oidc_issuer": "https://issuer.example.com",
                      "authorize_scopes": "openid"
                    }
                  }
                }
              },
              "Outputs": {
                "ProviderRef": {"Value": {"Ref": "Idp"}}
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
        stackName = TestFixtures.uniqueName("compat-cfn-cidp");
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
    @DisplayName("Ref is ProviderName, and ProviderDetails update in place before stack deletion")
    void providerDetailsUpdateInPlaceAndStackDeletionRemovesTheProvider() throws InterruptedException {
        createStack(parameters(p1, "ExampleOIDC", "original-client", false));
        assertIdentity("ExampleOIDC");
        assertProvider(p1, "ExampleOIDC", "original-client");

        updateStack(parameters(p1, "ExampleOIDC", "updated-client", false), "UPDATE_COMPLETE");
        assertIdentity("ExampleOIDC");
        assertProvider(p1, "ExampleOIDC", "updated-client");
        assertThat(providerNames(p1)).containsExactly("ExampleOIDC");

        deleteStack();
        assertProviderNotFound(p1, "ExampleOIDC");
        assertThat(providerNames(p1)).isEmpty();
    }

    @Test
    @DisplayName("Changing ProviderName replaces the provider and deletes the old one")
    void renamingReplacesTheProvider() throws InterruptedException {
        createStack(parameters(p1, "ExampleOIDC", "client", false));

        updateStack(parameters(p1, "RenamedOIDC", "client", false), "UPDATE_COMPLETE");

        assertIdentity("RenamedOIDC");
        assertProvider(p1, "RenamedOIDC", "client");
        assertThat(providerNames(p1)).containsExactly("RenamedOIDC");
    }

    @Test
    @DisplayName("Changing UserPoolId replaces the provider while Ref stays the ProviderName")
    void movingPoolsReplacesTheProvider() throws InterruptedException {
        createStack(parameters(p1, "ExampleOIDC", "client", false));

        updateStack(parameters(p2, "ExampleOIDC", "moved-client", false), "UPDATE_COMPLETE");

        assertIdentity("ExampleOIDC");
        assertProvider(p2, "ExampleOIDC", "moved-client");
        assertThat(providerNames(p1)).isEmpty();
        deleteStack();
        assertThat(providerNames(p2)).isEmpty();
    }

    @Test
    @DisplayName("A failed dependent resource rolls the in-place ProviderDetails update back")
    void aFailedDependentResourceRestoresTheProviderDetails() throws InterruptedException {
        createStack(parameters(p1, "ExampleOIDC", "original-client", false));

        updateStack(parameters(p1, "ExampleOIDC", "attempted-client", true), "UPDATE_ROLLBACK_COMPLETE");

        assertThat(cfn.describeStackEvents(r -> r.stackName(stackId)).stackEvents())
                .anySatisfy(event -> {
                    assertThat(event.logicalResourceId()).isEqualTo("Duplicate");
                    assertThat(event.resourceStatusAsString()).isIn("CREATE_FAILED", "UPDATE_FAILED");
                    assertThat(event.resourceStatusReason()).contains("already exists");
                });
        assertIdentity("ExampleOIDC");
        assertProvider(p1, "ExampleOIDC", "original-client");
        assertThat(providerNames(p1)).containsExactly("ExampleOIDC");
    }

    private static List<Parameter> parameters(String pool, String providerName, String clientId, boolean fail) {
        return List.of(
                Parameter.builder().parameterKey("UserPoolId").parameterValue(pool).build(),
                Parameter.builder().parameterKey("ProviderName").parameterValue(providerName).build(),
                Parameter.builder().parameterKey("ClientId").parameterValue(clientId).build(),
                Parameter.builder().parameterKey("Fail").parameterValue(Boolean.toString(fail)).build());
    }

    private void createStack(List<Parameter> parameters) throws InterruptedException {
        stackId = cfn.createStack(r -> r.stackName(stackName).templateBody(TEMPLATE).parameters(parameters)).stackId();
        awaitStatus("CREATE_COMPLETE");
    }

    private void updateStack(List<Parameter> parameters, String expected) throws InterruptedException {
        cfn.updateStack(r -> r.stackName(stackName).templateBody(TEMPLATE).parameters(parameters));
        awaitStatus(expected);
    }

    private void assertIdentity(String providerName) {
        assertThat(cfn.describeStacks(r -> r.stackName(stackId)).stacks().get(0).outputs())
                .filteredOn(o -> "ProviderRef".equals(o.outputKey())).extracting(Output::outputValue)
                .as("Ref is the bare ProviderName").containsExactly(providerName);
        assertThat(cfn.describeStackResource(r -> r.stackName(stackId).logicalResourceId("Idp"))
                .stackResourceDetail().physicalResourceId())
                .as("the physical id is the bare ProviderName").isEqualTo(providerName);
    }

    private IdentityProviderType provider(String pool, String providerName) {
        return cognito.describeIdentityProvider(r -> r.userPoolId(pool).providerName(providerName)).identityProvider();
    }

    private void assertProvider(String pool, String providerName, String clientId) {
        IdentityProviderType provider = provider(pool, providerName);
        assertThat(provider.userPoolId()).isEqualTo(pool);
        assertThat(provider.providerName()).isEqualTo(providerName);
        assertThat(provider.providerTypeAsString()).isEqualTo("OIDC");
        assertThat(provider.providerDetails()).containsEntry("client_id", clientId);
        assertThat(provider.attributeMapping()).containsEntry("email", "email");
    }

    private void assertProviderNotFound(String pool, String providerName) {
        assertThatThrownBy(() -> provider(pool, providerName)).isInstanceOf(ResourceNotFoundException.class);
    }

    private List<String> providerNames(String pool) {
        return cognito.listIdentityProvidersPaginator(r -> r.userPoolId(pool)).providers().stream()
                .map(ProviderDescription::providerName).sorted().toList();
    }

    private void awaitStatus(String expected) throws InterruptedException {
        TestFixtures.await(() -> {
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
        }, stackName, expected, TIMEOUT_NANOS, TestFixtures.isRealAws() ? 2_000 : 100);
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
        TestFixtures.await(() -> {
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
        }, stackName, "stack deletion", TIMEOUT_NANOS, TestFixtures.isRealAws() ? 2_000 : 100);
        stackId = null;
    }
}
