package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.sns.model.SnsSigningKey;
import io.github.hectorvent.floci.services.sns.model.Topic;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SnsMessageSignerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String BASE_URL = "http://localhost:4566";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void lambdaRecord_isSignedAndVerifiesAgainstServedCertificate() throws Exception {
        SnsMessageSigner signer = newSigner(AccountAwareStorageBackend.inMemory(ACCOUNT));
        LambdaService lambdaService = mock(LambdaService.class);
        SnsService service = new SnsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT), null, lambdaService, null, BASE_URL, MAPPER, signer);
        Topic topic = service.createTopic("signed-lambda-topic", null, null, REGION);
        String functionArn = "arn:aws:lambda:us-east-1:000000000000:function:signed";
        service.subscribe(topic.getTopicArn(), "lambda", functionArn, REGION, Map.of());

        service.publish(topic.getTopicArn(), null, "hello lambda", null, REGION);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(eq(REGION), eq(functionArn), payload.capture(), eq(InvocationType.Event));
        JsonNode sns = MAPPER.readTree(payload.getValue()).get("Records").get(0).get("Sns");

        assertEquals("1", sns.get("SignatureVersion").asText());
        assertTrue(sns.get("Subject").isNull());
        assertEquals(signer.certificateUrl(), sns.get("SigningCertUrl").asText());
        assertTrue(sns.get("UnsubscribeUrl").asText().startsWith(BASE_URL + "/?Action=Unsubscribe&SubscriptionArn="));
        assertTrue(verifies(signer, sns, "SigningCertUrl", "SHA1withRSA"));
    }

    @Test
    void signingFailure_doesNotDeliverAnUnsignedPlaceholder() {
        SnsMessageSigner signer = mock(SnsMessageSigner.class);
        doThrow(new IllegalStateException("Failed to sign SNS message"))
                .when(signer).sign(any(ObjectNode.class), anyString(), anyString());
        LambdaService lambdaService = mock(LambdaService.class);
        SnsService service = new SnsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT), null, lambdaService, null, BASE_URL, MAPPER, signer);
        Topic topic = service.createTopic("unsigned-lambda-topic", null, null, REGION);
        String functionArn = "arn:aws:lambda:us-east-1:000000000000:function:unsigned";
        service.subscribe(topic.getTopicArn(), "lambda", functionArn, REGION, Map.of());

        service.publish(topic.getTopicArn(), null, "hello lambda", null, REGION);

        verify(lambdaService, never()).invoke(anyString(), anyString(), any(byte[].class), any(InvocationType.class));
    }

    @Test
    void reset_generatesANewKeyInsteadOfRestoringTheOldOne() {
        AccountAwareStorageBackend<SnsSigningKey> store = AccountAwareStorageBackend.inMemory(ACCOUNT);
        SnsMessageSigner signer = newSigner(store);
        String before = signer.certificateUrl();

        store.clear();
        signer.clear();
        String after = signer.certificateUrl();

        assertNotEquals(before, after);
        assertEquals(after, newSigner(store).certificateUrl());
    }

    @Test
    void notificationCanonicalString_includesSubjectOnlyWhenPresent() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("Type", "Notification");
        node.put("MessageId", "id-1");
        node.put("TopicArn", "arn:aws:sns:us-east-1:000000000000:t");
        node.putNull("Subject");
        node.put("Message", "body");
        node.put("Timestamp", "2026-10-06T00:00:00.000Z");

        assertEquals("Message\nbody\nMessageId\nid-1\nTimestamp\n2026-10-06T00:00:00.000Z\n"
                + "TopicArn\narn:aws:sns:us-east-1:000000000000:t\nType\nNotification\n",
                SnsMessageSigner.canonicalString(node));

        node.put("Subject", "s");
        assertTrue(SnsMessageSigner.canonicalString(node).contains("MessageId\nid-1\nSubject\ns\nTimestamp\n"));
    }

    @Test
    void confirmationCanonicalString_usesSubscribeUrlAndToken() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("Type", "SubscriptionConfirmation");
        node.put("MessageId", "id-2");
        node.put("Token", "tok");
        node.put("TopicArn", "arn:aws:sns:us-east-1:000000000000:t");
        node.put("Message", "confirm");
        node.put("SubscribeURL", "http://localhost:4566/?Action=ConfirmSubscription");
        node.put("Timestamp", "2026-10-06T00:00:00.000Z");

        assertEquals("Message\nconfirm\nMessageId\nid-2\n"
                + "SubscribeURL\nhttp://localhost:4566/?Action=ConfirmSubscription\n"
                + "Timestamp\n2026-10-06T00:00:00.000Z\nToken\ntok\n"
                + "TopicArn\narn:aws:sns:us-east-1:000000000000:t\nType\nSubscriptionConfirmation\n",
                SnsMessageSigner.canonicalString(node));
    }

    @Test
    void signatureVersion2_signsWithSha256AndTamperingFails() throws Exception {
        SnsMessageSigner signer = newSigner(AccountAwareStorageBackend.inMemory(ACCOUNT));
        ObjectNode node = notification("payload");
        signer.sign(node, "2", "SigningCertURL");

        assertEquals("2", node.get("SignatureVersion").asText());
        assertTrue(verifies(signer, node, "SigningCertURL", "SHA256withRSA"));
        assertFalse(verifies(signer, node, "SigningCertURL", "SHA1withRSA"));

        node.put("Message", "forged");
        assertFalse(verifies(signer, node, "SigningCertURL", "SHA256withRSA"));
    }

    @Test
    void storedKey_isReusedAndANewKeyGetsANewUrl() {
        AccountAwareStorageBackend<SnsSigningKey> store = AccountAwareStorageBackend.inMemory(ACCOUNT);
        String first = newSigner(store).certificateUrl();
        String restarted = newSigner(store).certificateUrl();
        String fresh = newSigner(AccountAwareStorageBackend.inMemory(ACCOUNT)).certificateUrl();

        assertTrue(first.matches("http://localhost:4566/_aws/sns/SimpleNotificationService-[0-9a-f]{32}\\.pem"), first);
        assertEquals(first, restarted);
        assertNotEquals(first, fresh);
    }

    @Test
    void certificatePem_onlyForTheCurrentFileName() {
        SnsMessageSigner signer = newSigner(AccountAwareStorageBackend.inMemory(ACCOUNT));
        String url = signer.certificateUrl();
        String fileName = url.substring(url.lastIndexOf('/') + 1);

        assertTrue(signer.certificatePem(fileName).orElseThrow().startsWith("-----BEGIN CERTIFICATE-----"));
        assertTrue(signer.certificatePem("SimpleNotificationService-0.pem").isEmpty());
    }

    private static SnsMessageSigner newSigner(AccountAwareStorageBackend<SnsSigningKey> store) {
        return new SnsMessageSigner(store, ACCOUNT, new CertificateGenerator(), BASE_URL);
    }

    private static ObjectNode notification(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("Type", "Notification");
        node.put("MessageId", "6f1b4bb4-6c0c-4d6f-9d64-6d5b4d4d4d4d");
        node.put("TopicArn", "arn:aws:sns:us-east-1:000000000000:signed");
        node.put("Timestamp", "2026-10-06T10:00:00.000Z");
        node.put("Message", message);
        return node;
    }

    private static boolean verifies(SnsMessageSigner signer, JsonNode message, String certUrlField,
                                  String algorithm) throws Exception {
        String url = message.get(certUrlField).asText();
        String pem = signer.certificatePem(url.substring(url.lastIndexOf('/') + 1)).orElseThrow();
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        String type = message.get("Type").asText();
        String[] fields = "Notification".equals(type)
                ? new String[] {"Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type"}
                : new String[] {"Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type"};
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            JsonNode value = message.get(field);
            if (value != null && !value.isNull()) {
                canonical.append(field).append('\n').append(value.asText()).append('\n');
            }
        }
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(message.get("Signature").asText()));
    }
}
