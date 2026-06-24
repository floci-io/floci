package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iot.model.AuthorizerResult;
import io.github.hectorvent.floci.services.iot.model.IotAuthorizer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the custom-authorizer auth core (event construction, Lambda-response parsing,
 * policy evaluation, and token-signature verification) without an MQTT connection or a
 * deployed Lambda. The end-to-end {@code TestInvokeAuthorizer} path additionally requires
 * a running Lambda container and is covered manually.
 */
@QuarkusTest
class IotAuthorizerServiceTest {

    @Inject
    IotAuthorizerService service;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void buildEventProducesTokenBasedShape() throws Exception {
        IotAuthorizerService.AuthInput input =
                new IotAuthorizerService.AuthInput("the-token", null, "client-1", "user", "secret");
        byte[] payload = service.buildEvent(input, true);

        JsonNode event = objectMapper.readTree(payload);
        assertEquals("the-token", event.get("token").asText());
        assertTrue(event.get("signatureVerified").asBoolean());
        assertEquals("mqtt", event.get("protocols").get(0).asText());
        JsonNode mqtt = event.get("protocolData").get("mqtt");
        assertEquals("client-1", mqtt.get("clientId").asText());
        assertEquals("user", mqtt.get("username").asText());
        assertEquals("secret",
                new String(Base64.getDecoder().decode(mqtt.get("password").asText()), StandardCharsets.UTF_8));
    }

    @Test
    void parseResponseReadsAuthorizerFields() {
        String body = """
            {
              "isAuthenticated": true,
              "principalId": "device-123",
              "disconnectAfterInSeconds": 3600,
              "refreshAfterInSeconds": 300,
              "policyDocuments": [
                "{\\"Version\\":\\"2012-10-17\\",\\"Statement\\":[{\\"Effect\\":\\"Allow\\",\\"Action\\":\\"iot:Connect\\",\\"Resource\\":\\"*\\"}]}"
              ]
            }
            """;
        AuthorizerResult result = service.parseResponse(body.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isAuthenticated());
        assertEquals("device-123", result.principalId());
        assertEquals(3600, result.disconnectAfterSeconds());
        assertEquals(300, result.refreshAfterSeconds());
        assertEquals(1, result.policyDocuments().size());
    }

    @Test
    void parseResponseAcceptsInlinePolicyObjects() {
        String body = """
            {
              "isAuthenticated": true,
              "policyDocuments": [
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"iot:Publish","Resource":"*"}]}
              ]
            }
            """;
        AuthorizerResult result = service.parseResponse(body.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isAuthenticated());
        assertEquals(1, result.policyDocuments().size());
        assertTrue(service.isAllowed(result, "iot:Publish",
                "arn:aws:iot:us-east-1:000000000000:topic/devices/1/data", Map.of()));
    }

    @Test
    void isAllowedEvaluatesReturnedPolicy() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":["iot:Connect","iot:Subscribe"],
               "Resource":"arn:aws:iot:us-east-1:000000000000:topicfilter/devices/*"}
            ]}
            """;
        AuthorizerResult result = new AuthorizerResult(true, "p", List.of(policy), null, null);

        assertTrue(service.isAllowed(result, "iot:Subscribe",
                "arn:aws:iot:us-east-1:000000000000:topicfilter/devices/floor3", Map.of()));
        assertFalse(service.isAllowed(result, "iot:Publish",
                "arn:aws:iot:us-east-1:000000000000:topic/devices/floor3", Map.of()));
    }

    @Test
    void verifyTokenSignatureAcceptsValidRsaSignature() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        String token = "my-auth-token";
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(token.getBytes(StandardCharsets.UTF_8));
        String signatureB64 = Base64.getEncoder().encodeToString(signer.sign());

        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";

        IotAuthorizer authorizer = new IotAuthorizer();
        authorizer.setTokenSigningPublicKeys(Map.of("KEY1", pem));

        assertTrue(service.verifyTokenSignature(authorizer, token, signatureB64));
        assertFalse(service.verifyTokenSignature(authorizer, "tampered", signatureB64));
    }
}
