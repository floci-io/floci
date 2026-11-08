package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExplicitAuthFlowsType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserLambdaValidationException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sign-in denials must surface through the exception classes the AWS SDK models for Cognito:
 * a disabled user is {@code NotAuthorizedException} ("User is disabled."), and a PreAuthentication
 * trigger that raises is {@code UserLambdaValidationException} carrying the function's message.
 */
@DisplayName("Cognito IDP: sign-in denial exception classes")
class CognitoSignInDenialTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static final String PASSWORD = "CompatPass1!";
    private static final String DENIAL_MESSAGE = "Email not verified";
    private static final String PRE_AUTH_SOURCE = """
            def handler(event, context):
                raise Exception("%s")
            """.formatted(DENIAL_MESSAGE);

    private static CognitoIdentityProviderClient cognito;
    private static LambdaClient lambda;
    private static String poolId;
    private static String clientId;
    private static String triggerPoolId;
    private static String triggerClientId;
    private static String triggerFunctionName;

    @BeforeAll
    static void setup() {
        cognito = TestFixtures.cognitoClient();
        lambda = TestFixtures.lambdaClient();

        poolId = cognito.createUserPool(b -> b.poolName(TestFixtures.uniqueName("compat-denial-pool")))
                .userPool().id();
        clientId = createPasswordClient(poolId);
    }

    @AfterAll
    static void cleanup() {
        if (cognito != null) {
            deletePoolQuietly(poolId);
            deletePoolQuietly(triggerPoolId);
            cognito.close();
        }
        if (lambda != null) {
            if (triggerFunctionName != null) {
                try {
                    lambda.deleteFunction(b -> b.functionName(triggerFunctionName));
                } catch (Exception ignored) {
                    // Cleanup only. A failed delete must not hide the test result.
                }
            }
            lambda.close();
        }
    }

    @Test
    @DisplayName("InitiateAuth for a disabled user is NotAuthorizedException: User is disabled.")
    void initiateAuthDisabledUserIsNotAuthorized() {
        String username = createConfirmedUser(poolId, "disabled-initiate");
        cognito.adminDisableUser(b -> b.userPoolId(poolId).username(username));

        assertThatThrownBy(() -> cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", username, "PASSWORD", PASSWORD))))
                .isInstanceOfSatisfying(NotAuthorizedException.class, e -> {
                    assertThat(e.awsErrorDetails().errorMessage()).isEqualTo("User is disabled.");
                    assertThat(e.statusCode()).isEqualTo(400);
                });
    }

    @Test
    @DisplayName("AdminInitiateAuth for a disabled user is NotAuthorizedException: User is disabled.")
    void adminInitiateAuthDisabledUserIsNotAuthorized() {
        String username = createConfirmedUser(poolId, "disabled-admin");
        cognito.adminDisableUser(b -> b.userPoolId(poolId).username(username));

        assertThatThrownBy(() -> cognito.adminInitiateAuth(b -> b
                .userPoolId(poolId)
                .clientId(clientId)
                .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", username, "PASSWORD", PASSWORD))))
                .isInstanceOfSatisfying(NotAuthorizedException.class, e -> {
                    assertThat(e.awsErrorDetails().errorMessage()).isEqualTo("User is disabled.");
                    assertThat(e.statusCode()).isEqualTo(400);
                });
    }

    @Test
    @DisplayName("a raising PreAuthentication trigger is UserLambdaValidationException with the function's message")
    void preAuthenticationTriggerErrorIsUserLambdaValidationException() {
        Assumptions.assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "skipping: Lambda dispatch (Docker) not available in this environment");

        triggerFunctionName = TestFixtures.uniqueName("compat-pre-auth-deny");
        String functionArn = lambda.createFunction(b -> b
                .functionName(triggerFunctionName)
                .runtime(Runtime.PYTHON3_12)
                .role(ROLE)
                .handler("lambda_function.handler")
                .timeout(10)
                .code(c -> c.zipFile(SdkBytes.fromByteArray(zip("lambda_function.py", PRE_AUTH_SOURCE)))))
                .functionArn();

        triggerPoolId = cognito.createUserPool(b -> b
                .poolName(TestFixtures.uniqueName("compat-denial-trigger-pool"))
                .lambdaConfig(l -> l.preAuthentication(functionArn)))
                .userPool().id();
        triggerClientId = createPasswordClient(triggerPoolId);
        String username = createConfirmedUser(triggerPoolId, "pre-auth-denied");

        assertThatThrownBy(() -> cognito.initiateAuth(b -> b
                .clientId(triggerClientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", username, "PASSWORD", PASSWORD))))
                .isInstanceOfSatisfying(UserLambdaValidationException.class, e -> {
                    assertThat(e.awsErrorDetails().errorMessage())
                            .isEqualTo("PreAuthentication failed with error " + DENIAL_MESSAGE + ".");
                    assertThat(e.statusCode()).isEqualTo(400);
                });

        assertThatThrownBy(() -> cognito.adminInitiateAuth(b -> b
                .userPoolId(triggerPoolId)
                .clientId(triggerClientId)
                .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", username, "PASSWORD", PASSWORD))))
                .isInstanceOfSatisfying(UserLambdaValidationException.class, e ->
                        assertThat(e.awsErrorDetails().errorMessage())
                                .isEqualTo("PreAuthentication failed with error " + DENIAL_MESSAGE + "."));
    }

    private static String createPasswordClient(String userPoolId) {
        return cognito.createUserPoolClient(b -> b
                .userPoolId(userPoolId)
                .clientName("compat-denial-client")
                .explicitAuthFlows(
                        ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH,
                        ExplicitAuthFlowsType.ALLOW_ADMIN_USER_PASSWORD_AUTH))
                .userPoolClient().clientId();
    }

    private static String createConfirmedUser(String userPoolId, String prefix) {
        String username = TestFixtures.uniqueName(prefix);
        cognito.adminCreateUser(b -> b
                .userPoolId(userPoolId)
                .username(username)
                .userAttributes(
                        AttributeType.builder().name("email").value(username + "@example.com").build(),
                        AttributeType.builder().name("email_verified").value("true").build())
                .messageAction(MessageActionType.SUPPRESS));
        cognito.adminSetUserPassword(b -> b
                .userPoolId(userPoolId)
                .username(username)
                .password(PASSWORD)
                .permanent(true));
        return username;
    }

    private static void deletePoolQuietly(String userPoolId) {
        if (userPoolId == null) {
            return;
        }
        try {
            cognito.deleteUserPool(b -> b.userPoolId(userPoolId));
        } catch (Exception ignored) {
            // Cleanup only. A failed delete must not hide the test result.
        }
    }

    private static byte[] zip(String entryName, String content) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(content.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot build the Lambda deployment package", e);
        }
    }
}
