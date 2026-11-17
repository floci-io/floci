package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeDataType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExplicitAuthFlowsType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidParameterException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SchemaAttributeType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Cognito custom attribute schema validation (issue #7994)")
class CognitoCustomAttributeSchemaTest {

    private static final String PASSWORD = "Valid-password123!";

    @ParameterizedTest
    @CsvSource({
            "AdminCreateUser, create", "AdminCreateUser, add",
            "SignUp, create", "SignUp, add",
            "AdminUpdateUserAttributes, create", "AdminUpdateUserAttributes, add",
            "UpdateUserAttributes, create", "UpdateUserAttributes, add"
    })
    @DisplayName("All user writes reject undeclared attributes atomically and accept declared attributes")
    void rejectsUndeclaredAttributesWithoutSavingAnyChanges(String action, String schemaSource) {
        try (CognitoIdentityProviderClient cognito = TestFixtures.cognitoClient()) {
            SchemaAttributeType known = SchemaAttributeType.builder().name("known")
                    .attributeDataType(AttributeDataType.STRING).mutable(true).build();
            String poolId = cognito.createUserPool(b -> b.poolName(TestFixtures.uniqueName("custom-schema"))
                    .schema(schemaSource.equals("create") ? List.of(known) : List.of())).userPool().id();
            try {
                if (schemaSource.equals("add")) {
                    cognito.addCustomAttributes(b -> b.userPoolId(poolId).customAttributes(known));
                }
                String clientId = createClient(cognito, poolId);
                cognito.adminCreateUser(b -> b.userPoolId(poolId).username("existing")
                        .messageAction(MessageActionType.SUPPRESS).userAttributes(
                                attribute("email", "original@example.com"), attribute("custom:known", "original")));
                String accessToken = signIn(cognito, poolId, clientId);
                List<AttributeType> original = cognito.adminGetUser(b -> b.userPoolId(poolId)
                        .username("existing")).userAttributes();
                String username = action.equals("AdminCreateUser") || action.equals("SignUp") ? "new-user" : "existing";
                List<AttributeType> invalid = List.of(attribute("email", "changed@example.com"),
                        attribute("custom:known", "changed"), attribute("custom:undeclared", "x"));

                assertThatThrownBy(() -> write(cognito, action, poolId, clientId, username, accessToken, invalid))
                        .isInstanceOf(InvalidParameterException.class)
                        .hasMessageContaining("Attribute does not exist in the schema");
                assertThat(cognito.adminGetUser(b -> b.userPoolId(poolId).username("existing")).userAttributes())
                        .containsExactlyInAnyOrderElementsOf(original);
                assertThat(cognito.listUsers(b -> b.userPoolId(poolId)).users()).hasSize(1);

                write(cognito, action, poolId, clientId, username, accessToken,
                        List.of(attribute("email", "changed@example.com"), attribute("custom:known", "changed")));
                assertThat(cognito.adminGetUser(b -> b.userPoolId(poolId).username(username)).userAttributes())
                        .contains(attribute("custom:known", "changed"), attribute("email", "changed@example.com"));
            } finally {
                cognito.deleteUserPool(b -> b.userPoolId(poolId));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"AdminCreateUser", "SignUp", "AdminUpdateUserAttributes", "UpdateUserAttributes"})
    @DisplayName("A pool without a custom schema rejects custom attributes")
    void rejectsCustomAttributesWhenNoSchemaWasDeclared(String action) {
        try (CognitoIdentityProviderClient cognito = TestFixtures.cognitoClient()) {
            String poolId = cognito.createUserPool(b -> b.poolName(TestFixtures.uniqueName("no-custom-schema")))
                    .userPool().id();
            try {
                String clientId = createClient(cognito, poolId);
                cognito.adminCreateUser(b -> b.userPoolId(poolId).username("existing")
                        .messageAction(MessageActionType.SUPPRESS));
                String accessToken = signIn(cognito, poolId, clientId);
                String username = action.equals("AdminCreateUser") || action.equals("SignUp") ? "new-user" : "existing";

                assertThatThrownBy(() -> write(cognito, action, poolId, clientId, username, accessToken,
                        List.of(attribute("custom:undeclared", "x"))))
                        .isInstanceOf(InvalidParameterException.class)
                        .hasMessageContaining("Attribute does not exist in the schema");
                assertThat(cognito.listUsers(b -> b.userPoolId(poolId)).users()).hasSize(1);
                assertThat(cognito.adminGetUser(b -> b.userPoolId(poolId).username("existing")).userAttributes())
                        .extracting(AttributeType::name).doesNotContain("custom:undeclared");
            } finally {
                cognito.deleteUserPool(b -> b.userPoolId(poolId));
            }
        }
    }

    private static String createClient(CognitoIdentityProviderClient cognito, String poolId) {
        return cognito.createUserPoolClient(b -> b.userPoolId(poolId).clientName("custom-schema-client")
                .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH)).userPoolClient().clientId();
    }

    private static String signIn(CognitoIdentityProviderClient cognito, String poolId, String clientId) {
        cognito.adminSetUserPassword(b -> b.userPoolId(poolId).username("existing").password(PASSWORD).permanent(true));
        return cognito.initiateAuth(b -> b.clientId(clientId).authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", "existing", "PASSWORD", PASSWORD)))
                .authenticationResult().accessToken();
    }

    private static AttributeType attribute(String name, String value) {
        return AttributeType.builder().name(name).value(value).build();
    }

    private static void write(CognitoIdentityProviderClient cognito, String action, String poolId, String clientId,
                              String username, String accessToken, List<AttributeType> attributes) {
        switch (action) {
            case "AdminCreateUser" -> cognito.adminCreateUser(b -> b.userPoolId(poolId).username(username)
                    .messageAction(MessageActionType.SUPPRESS).userAttributes(attributes));
            case "SignUp" -> cognito.signUp(b -> b.clientId(clientId).username(username).password(PASSWORD)
                    .userAttributes(attributes));
            case "AdminUpdateUserAttributes" -> cognito.adminUpdateUserAttributes(b -> b.userPoolId(poolId)
                    .username(username).userAttributes(attributes));
            case "UpdateUserAttributes" -> cognito.updateUserAttributes(b -> b.accessToken(accessToken)
                    .userAttributes(attributes));
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        }
    }
}
