package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CreateUserPoolResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.VerifiedAttributeType;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compatibility tests for Cognito ConfirmSignUp verified attribute updates (Issue #1654).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoConfirmSignUpVerificationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Pattern SIX_DIGIT_CODE_PATTERN = Pattern.compile("\\b(\\d{6})\\b");
    private static final String PASSWORD = "Passw0rd!";

    private static CognitoIdentityProviderClient cognitoClient;
    private String poolId;

    @BeforeAll
    static void setup() {
        cognitoClient = TestFixtures.cognitoClient();
    }

    @AfterAll
    static void cleanup() {
        if (cognitoClient != null) {
            cognitoClient.close();
        }
    }

    @AfterEach
    void tearDown() {
        if (poolId != null) {
            try {
                cognitoClient.deleteUserPool(b -> b.userPoolId(poolId));
            } catch (Exception ignored) {
            }
            poolId = null;
        }
    }

    @Test
    @Order(1)
    @DisplayName("ConfirmSignUp marks email_verified=true when email is the auto verified attribute")
    void confirmSignUpMarksEmailVerified() throws Exception {
        clearInspectionEndpoint("/_aws/ses");

        String poolName = "email-verify-" + UUID.randomUUID();

        CreateUserPoolResponse poolResp = cognitoClient.createUserPool(b -> b
                .poolName(poolName)
                .autoVerifiedAttributes(VerifiedAttributeType.EMAIL));
        poolId = poolResp.userPool().id();

        String clientId = cognitoClient.createUserPoolClient(b -> b
                        .userPoolId(poolId)
                        .clientName("email-verify-client"))
                .userPoolClient().clientId();

        String email = "alice+" + UUID.randomUUID() + "@example.com";

        cognitoClient.signUp(b -> b
                .clientId(clientId)
                .username(email)
                .password(PASSWORD)
                .userAttributes(AttributeType.builder()
                        .name("email")
                        .value(email)
                        .build()));

        String confirmationCode = fetchVerificationCodeFromSes(email);

        cognitoClient.confirmSignUp(b -> b
                .clientId(clientId)
                .username(email)
                .confirmationCode(confirmationCode));

        AdminGetUserResponse user = cognitoClient.adminGetUser(b -> b
                .userPoolId(poolId)
                .username(email));

        assertThat(user.userStatusAsString())
                .as("ConfirmSignUp should confirm the user")
                .isEqualTo("CONFIRMED");

        String emailVerified = makeAttributeValue(user.userAttributes(), "email_verified");
        assertThat(emailVerified)
                .as("ConfirmSignUp should set email_verified=true for the email auto verified attribute")
                .isEqualTo("true");
    }

    @Test
    @Order(2)
    @DisplayName("ConfirmSignUp marks phone_number_verified=true when phone_number is the auto verified attribute")
    void confirmSignUpMarksPhoneNumberVerified() throws Exception {
        clearInspectionEndpoint("/_aws/sns");

        String poolName = "phone-verify-" + UUID.randomUUID();

        CreateUserPoolResponse poolResp = cognitoClient.createUserPool(b -> b
                .poolName(poolName)
                .autoVerifiedAttributes(VerifiedAttributeType.PHONE_NUMBER));
        poolId = poolResp.userPool().id();

        String clientId = cognitoClient.createUserPoolClient(b -> b
                        .userPoolId(poolId)
                        .clientName("phone-verify-client"))
                .userPoolClient().clientId();

        String username = "phone-user-" + UUID.randomUUID();
        String phoneNumber = "+49170" + ThreadLocalRandom.current().nextLong(1_000_000, 10_000_000);

        cognitoClient.signUp(b -> b
                .clientId(clientId)
                .username(username)
                .password(PASSWORD)
                .userAttributes(AttributeType.builder()
                        .name("phone_number")
                        .value(phoneNumber)
                        .build()));

        String confirmationCode = fetchVerificationCodeFromSns(phoneNumber);

        cognitoClient.confirmSignUp(b -> b
                .clientId(clientId)
                .username(username)
                .confirmationCode(confirmationCode));

        AdminGetUserResponse user = cognitoClient.adminGetUser(b -> b
                .userPoolId(poolId)
                .username(username));

        assertThat(user.userStatusAsString())
                .as("ConfirmSignUp should confirm the user")
                .isEqualTo("CONFIRMED");

        String phoneVerified = makeAttributeValue(user.userAttributes(), "phone_number_verified");
        assertThat(phoneVerified)
                .as("ConfirmSignUp should set phone_number_verified=true for the phone_number auto-verified attribute (Issue #1654)")
                .isEqualTo("true");
    }

    private static String makeAttributeValue(List<AttributeType> attributes, String name) {
        return attributes.stream()
                .filter(a -> name.equals(a.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElse(null);
    }

    private static void clearInspectionEndpoint(String path) throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(TestFixtures.endpoint().resolve(path))
                        .DELETE()
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(resp.statusCode())
                .as("clearing inspection endpoint %s should succeed", path)
                .isEqualTo(200);
    }

    private static String fetchVerificationCodeFromSes(String recipient) throws Exception {
        URI uri = TestFixtures.endpoint()
                .resolve("/_aws/ses?email=" + URLEncoder.encode(recipient, StandardCharsets.UTF_8));

        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(uri)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        JsonNode messages = JSON.readTree(resp.body()).path("messages");
        assertThat(messages.isArray() && !messages.isEmpty())
                .as("SES should have received a verification email for %s", recipient)
                .isTrue();

        String body = messages.get(0).path("Body").path("text_part").asText();
        return extractSixDigitCode(body);
    }

    private static String fetchVerificationCodeFromSns(String phoneNumber) throws Exception {
        URI uri = TestFixtures.endpoint()
                .resolve("/_aws/sns?phone=" + URLEncoder.encode(phoneNumber, StandardCharsets.UTF_8));

        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(uri)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        JsonNode messages = JSON.readTree(resp.body()).path("messages");
        assertThat(messages.isArray() && !messages.isEmpty())
                .as("SNS should have received a verification SMS for %s", phoneNumber)
                .isTrue();

        String body = messages.get(0).path("Message").asText();
        return extractSixDigitCode(body);
    }

    private static String extractSixDigitCode(String body) {
        Matcher matcher = SIX_DIGIT_CODE_PATTERN.matcher(body);
        assertThat(matcher.find())
                .as("Verification message body should contain a 6-digit code")
                .isTrue();

        return matcher.group(1);
    }

}
