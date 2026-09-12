package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExpiredCodeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExplicitAuthFlowsType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CognitoVerifyUserAttributeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Pattern SIX_DIGIT_CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final String PASSWORD = "Verify1234!";

    private static CognitoIdentityProviderClient cognito;

    private String poolId;

    @BeforeAll
    static void setUpClient() {
        cognito = TestFixtures.cognitoClient();
    }

    @AfterAll
    static void closeClient() {
        if (cognito != null) {
            cognito.close();
        }
    }

    @AfterEach
    void deletePool() {
        if (poolId != null) {
            cognito.deleteUserPool(b -> b.userPoolId(poolId));
            poolId = null;
        }
    }

    @Test
    void verifiesEmailAndPhoneNumberWithAwsSdk() throws Exception {
        poolId = cognito.createUserPool(b -> b.poolName("verify-attribute-" + UUID.randomUUID()))
                .userPool()
                .id();
        String clientId = cognito.createUserPoolClient(b -> b
                        .userPoolId(poolId)
                        .clientName("verify-attribute-client")
                        .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH))
                .userPoolClient()
                .clientId();

        String username = "verify-" + UUID.randomUUID();
        String email = username + "@example.com";
        String phoneNumber = "+1555" + ThreadLocalRandom.current().nextLong(1_000_000, 10_000_000);

        cognito.adminCreateUser(b -> b
                .userPoolId(poolId)
                .username(username)
                .messageAction(MessageActionType.SUPPRESS)
                .userAttributes(
                        AttributeType.builder().name("email").value(email).build(),
                        AttributeType.builder().name("phone_number").value(phoneNumber).build()));
        cognito.adminSetUserPassword(b -> b
                .userPoolId(poolId)
                .username(username)
                .password(PASSWORD)
                .permanent(true));

        String accessToken = cognito.initiateAuth(b -> b
                        .clientId(clientId)
                        .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                        .authParameters(Map.of("USERNAME", username, "PASSWORD", PASSWORD)))
                .authenticationResult()
                .accessToken();

        clearInspectionEndpoint("/_aws/ses");
        clearInspectionEndpoint("/_aws/sns");

        var emailDelivery = cognito.getUserAttributeVerificationCode(b -> b
                        .accessToken(accessToken)
                        .attributeName("email"))
                .codeDeliveryDetails();
        assertThat(emailDelivery.attributeName()).isEqualTo("email");
        assertThat(emailDelivery.deliveryMediumAsString()).isEqualTo("EMAIL");
        assertThat(emailDelivery.destination()).isEqualTo("v***@e***");
        String emailCode = fetchLatestSesVerificationCode(email);

        var phoneDelivery = cognito.getUserAttributeVerificationCode(b -> b
                        .accessToken(accessToken)
                        .attributeName("phone_number"))
                .codeDeliveryDetails();
        assertThat(phoneDelivery.attributeName()).isEqualTo("phone_number");
        assertThat(phoneDelivery.deliveryMediumAsString()).isEqualTo("SMS");
        assertThat(phoneDelivery.destination()).isEqualTo(maskedPhoneNumber(phoneNumber));
        String phoneCode = fetchLatestSnsVerificationCode(phoneNumber);

        assertThatThrownBy(() -> cognito.verifyUserAttribute(b -> b
                .accessToken(accessToken)
                .attributeName("email")
                .code(differentCode(emailCode))))
                .isInstanceOf(CodeMismatchException.class);
        assertThat(userAttribute(username, "email_verified")).isNull();

        cognito.verifyUserAttribute(b -> b
                .accessToken(accessToken)
                .attributeName("email")
                .code(emailCode));
        cognito.verifyUserAttribute(b -> b
                .accessToken(accessToken)
                .attributeName("phone_number")
                .code(phoneCode));

        assertThat(userAttribute(username, "email_verified")).isEqualTo("true");
        assertThat(userAttribute(username, "phone_number_verified")).isEqualTo("true");

        assertThatThrownBy(() -> cognito.verifyUserAttribute(b -> b
                .accessToken(accessToken)
                .attributeName("email")
                .code(emailCode)))
                .isInstanceOfSatisfying(ExpiredCodeException.class,
                        exception -> assertThat(exception.awsErrorDetails().errorMessage())
                                .isEqualTo("Invalid code provided, please request a code again."));

        clearInspectionEndpoint("/_aws/ses");
        clearInspectionEndpoint("/_aws/sns");

        cognito.getUserAttributeVerificationCode(b -> b
                .accessToken(accessToken)
                .attributeName("email"));
        String repeatedEmailCode = fetchLatestSesVerificationCode(email);

        cognito.getUserAttributeVerificationCode(b -> b
                .accessToken(accessToken)
                .attributeName("phone_number"));
        String repeatedPhoneCode = fetchLatestSnsVerificationCode(phoneNumber);

        cognito.verifyUserAttribute(b -> b
                .accessToken(accessToken)
                .attributeName("email")
                .code(repeatedEmailCode));
        cognito.verifyUserAttribute(b -> b
                .accessToken(accessToken)
                .attributeName("phone_number")
                .code(repeatedPhoneCode));

        assertThat(userAttribute(username, "email_verified")).isEqualTo("true");
        assertThat(userAttribute(username, "phone_number_verified")).isEqualTo("true");
    }

    private String userAttribute(String username, String attributeName) {
        return cognito.adminGetUser(b -> b.userPoolId(poolId).username(username))
                .userAttributes()
                .stream()
                .filter(attribute -> attributeName.equals(attribute.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElse(null);
    }

    private static void clearInspectionEndpoint(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(TestFixtures.endpoint().resolve(path))
                        .DELETE()
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static String fetchLatestSesVerificationCode(String recipient) throws Exception {
        URI uri = TestFixtures.endpoint()
                .resolve("/_aws/ses?email=" + URLEncoder.encode(recipient, StandardCharsets.UTF_8));
        JsonNode messages = getInspectionMessages(uri);
        return extractVerificationCode(messages.get(0).path("Body").path("text_part").asText());
    }

    private static String fetchLatestSnsVerificationCode(String phoneNumber) throws Exception {
        URI uri = TestFixtures.endpoint()
                .resolve("/_aws/sns?phone=" + URLEncoder.encode(phoneNumber, StandardCharsets.UTF_8));
        JsonNode messages = getInspectionMessages(uri);
        return extractVerificationCode(messages.get(0).path("Message").asText());
    }

    private static JsonNode getInspectionMessages(URI uri) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(uri)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode messages = JSON.readTree(response.body()).path("messages");
        assertThat(messages.isArray() && !messages.isEmpty()).isTrue();
        return messages;
    }

    private static String extractVerificationCode(String message) {
        Matcher matcher = SIX_DIGIT_CODE.matcher(message);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String differentCode(String code) {
        return "000000".equals(code) ? "000001" : "000000";
    }

    private static String maskedPhoneNumber(String phoneNumber) {
        return "+" + "*".repeat(phoneNumber.length() - 5)
                + phoneNumber.substring(phoneNumber.length() - 4);
    }
}
