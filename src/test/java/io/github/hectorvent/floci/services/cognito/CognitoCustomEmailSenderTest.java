package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.verification.CognitoMessageDispatcher;
import io.github.hectorvent.floci.services.cognito.verification.CustomSenderCodeEncryptor;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The CustomEmailSender trigger: Cognito hands its email codes, encrypted under the pool's KMS key
 * as AWS Encryption SDK messages, to a Lambda function instead of sending the email itself.
 */
class CognitoCustomEmailSenderTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String SENDER = "arn:aws:lambda:us-east-1:000000000000:function:email-sender";
    private static final String CUSTOM_MESSAGE = "arn:aws:lambda:us-east-1:000000000000:function:custom-message";
    private static final List<String> AUTH_FLOWS = List.of("ALLOW_USER_PASSWORD_AUTH", "ALLOW_USER_AUTH",
            "ALLOW_REFRESH_TOKEN_AUTH");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KmsService kms;
    private String keyArn;
    private LambdaService lambda;
    private SesService ses;
    private SnsService sns;
    private CognitoService service;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory();
        kms = new KmsService(storageFactory, regionResolver);
        keyArn = kms.createKey("custom sender codes", REGION).getArn();
        lambda = mock(LambdaService.class);
        ses = mock(SesService.class);
        sns = mock(SnsService.class);
        service = new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", "cloudfront.net", regionResolver, lambda, mock(AcmService.class),
                new VerificationCodeService(storageFactory, Clock.systemUTC()),
                new CognitoMessageDispatcher(ses, sns, REGION), mock(TlsCertificateManager.class),
                Clock.systemUTC(), new CustomSenderCodeEncryptor(kms));
    }

    @Test
    @SuppressWarnings("unchecked")
    void signUpHandsTheEncryptedCodeToTheFunctionInsteadOfSendingEmail() throws Exception {
        UserPool pool = pool(Map.of("KMSKeyID", keyArn, "CustomEmailSender", sender(),
                "CustomMessage", CUSTOM_MESSAGE), List.of("email"));
        UserPoolClient client = client(pool);

        service.signUp(client.getClientId(), "alice", "Passw0rd!", Map.of("email", "alice@example.com"));

        Map<String, Object> event = senderEvent();
        assertEquals("1", event.get("version"));
        assertEquals("CustomEmailSender_SignUp", event.get("triggerSource"));
        assertEquals(REGION, event.get("region"));
        assertEquals(pool.getId(), event.get("userPoolId"));
        assertEquals("alice", event.get("userName"));
        assertEquals(client.getClientId(), ((Map<String, Object>) event.get("callerContext")).get("clientId"));
        Map<String, Object> request = (Map<String, Object>) event.get("request");
        assertEquals("customEmailSenderRequestV1", request.get("type"));
        assertTrue(request.containsKey("clientMetadata"));
        assertNull(request.get("clientMetadata"));
        Map<String, Object> userAttributes = (Map<String, Object>) request.get("userAttributes");
        assertEquals("alice@example.com", userAttributes.get("email"));
        assertEquals("UNCONFIRMED", userAttributes.get("cognito:user_status"));

        verify(ses, never()).sendEmail(any());
        verify(lambda, never()).invoke(anyString(), eq(CUSTOM_MESSAGE), any(byte[].class), any());

        service.confirmSignUp(client.getClientId(), "alice", decrypt(request).plaintext());
        assertEquals("CONFIRMED", service.adminGetUser(pool.getId(), "alice").getUserStatus());
    }

    @Test
    void theCodeIsAnEncryptionSdkMessageUnderThePoolKey() throws Exception {
        UserPool pool = pool(Map.of("KMSKeyID", keyArn, "CustomEmailSender", sender()), List.of("email"));
        UserPoolClient client = client(pool);

        service.signUp(client.getClientId(), "alice", "Passw0rd!", Map.of("email", "alice@example.com"));

        DecryptedMessage message = decrypt(request(senderEvent()));
        assertEquals(0x0378, message.algorithmSuiteId());
        assertEquals("aws-kms", message.keyProviderId());
        assertEquals(keyArn, message.keyProviderInfo());
        assertEquals(pool.getId(), message.encryptionContext().get("userpool-id"));
        assertTrue(message.encryptionContext().containsKey("aws-crypto-public-key"));
        assertEquals(103, message.signatureLength());
        assertTrue(message.plaintext().matches("\\d{6}"), message.plaintext());
    }

    @Test
    void resendAndForgotPasswordCodesNameTheirTriggerSources() throws Exception {
        UserPool pool = pool(Map.of("KMSKeyID", keyArn, "CustomEmailSender", sender()), List.of("email"));
        UserPoolClient client = client(pool);
        service.signUp(client.getClientId(), "alice", "Passw0rd!", Map.of("email", "alice@example.com"));

        service.resendConfirmationCode(client.getClientId(), "alice");
        Map<String, Object> resent = senderEvent();
        assertEquals("CustomEmailSender_ResendCode", resent.get("triggerSource"));
        service.confirmSignUp(client.getClientId(), "alice", decrypt(request(resent)).plaintext());

        service.forgotPassword(client.getClientId(), "alice");
        Map<String, Object> forgot = senderEvent();
        assertEquals("CustomEmailSender_ForgotPassword", forgot.get("triggerSource"));
        service.confirmForgotPassword(client.getClientId(), "alice", decrypt(request(forgot)).plaintext(),
                "N3wPassw0rd!");
        assertNotNull(signIn(client, "alice", "N3wPassw0rd!"));
        verify(ses, never()).sendEmail(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void attributeVerificationCodesGoToTheFunction() throws Exception {
        UserPool pool = pool(Map.of("KMSKeyID", keyArn, "CustomEmailSender", sender()), List.of());
        UserPoolClient client = client(pool);
        for (String username : List.of("alice", "bob")) {
            service.adminCreateUser(pool.getId(), username, Map.of("email", username + "@example.com"), null);
            service.adminSetUserPassword(pool.getId(), username, "Passw0rd!", true);
        }

        String aliceToken = signIn(client, "alice", "Passw0rd!");
        service.updateUserAttributes(aliceToken, Map.of("email", "alice@example.org"));
        Map<String, Object> updated = senderEvent();
        assertEquals("CustomEmailSender_UpdateUserAttribute", updated.get("triggerSource"));
        assertEquals(client.getClientId(), ((Map<String, Object>) updated.get("callerContext")).get("clientId"));
        assertEquals("alice@example.org",
                ((Map<String, Object>) request(updated).get("userAttributes")).get("email"));
        service.verifyUserAttribute(aliceToken, "email", decrypt(request(updated)).plaintext());
        assertEquals("true", service.adminGetUser(pool.getId(), "alice").getAttributes().get("email_verified"));

        String bobToken = signIn(client, "bob", "Passw0rd!");
        service.getUserAttributeVerificationCode(bobToken, "email");
        Map<String, Object> verification = senderEvent();
        assertEquals("CustomEmailSender_VerifyUserAttribute", verification.get("triggerSource"));
        assertEquals("bob", verification.get("userName"));
        service.verifyUserAttribute(bobToken, "email", decrypt(request(verification)).plaintext());
        assertEquals("true", service.adminGetUser(pool.getId(), "bob").getAttributes().get("email_verified"));
        verify(ses, never()).sendEmail(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmailSignInCodeGoesToTheFunctionWithTheClientMetadata() throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("PoolName", "sender-pool");
        request.put("Policies", Map.of("SignInPolicy",
                Map.of("AllowedFirstAuthFactors", List.of("PASSWORD", "EMAIL_OTP"))));
        request.put("LambdaConfig", Map.of("KMSKeyID", keyArn, "CustomEmailSender", sender()));
        UserPool pool = service.createUserPool(request, REGION);
        UserPoolClient client = client(pool);
        service.adminCreateUser(pool.getId(), "alice",
                Map.of("email", "alice@example.com", "email_verified", "true"), null);
        service.adminSetUserPassword(pool.getId(), "alice", "Passw0rd!", true);

        Map<String, Object> challenge = service.initiateAuth(client.getClientId(), "USER_AUTH",
                Map.of("USERNAME", "alice", "PREFERRED_CHALLENGE", "EMAIL_OTP"), Map.of("app", "web"));
        assertEquals("EMAIL_OTP", challenge.get("ChallengeName"));
        Map<String, Object> event = senderEvent();
        assertEquals("CustomEmailSender_Authentication", event.get("triggerSource"));
        assertEquals(Map.of("app", "web"), request(event).get("clientMetadata"));

        Map<String, Object> result = service.respondToAuthChallenge(client.getClientId(), "EMAIL_OTP",
                (String) challenge.get("Session"),
                Map.of("USERNAME", "alice", "EMAIL_OTP_CODE", decrypt(request(event)).plaintext()));
        assertNotNull(result.get("AuthenticationResult"));
        verify(ses, never()).sendEmail(any());
    }

    @Test
    void smsCodesStillGoThroughSns() {
        UserPool pool = pool(Map.of("KMSKeyID", keyArn, "CustomEmailSender", sender()), List.of("phone_number"));
        UserPoolClient client = client(pool);

        service.signUp(client.getClientId(), "alice", "Passw0rd!", Map.of("phone_number", "+15555550100"));

        verify(sns).publish(any(), any(), eq("+15555550100"), anyString(), any(), any(), eq(REGION));
        verify(lambda, never()).invoke(anyString(), anyString(), any(byte[].class), any());
    }

    @Test
    void aPoolWithoutAKmsKeyCannotDeliverTheCode() {
        UserPool pool = pool(Map.of("CustomEmailSender", sender()), List.of("email"));
        UserPoolClient client = client(pool);

        AwsException e = assertThrows(AwsException.class, () -> service.signUp(client.getClientId(), "alice",
                "Passw0rd!", Map.of("email", "alice@example.com")));
        assertEquals("CodeDeliveryFailureException", e.getErrorCode());
        assertThrows(AwsException.class, () -> service.adminGetUser(pool.getId(), "alice"));
        verify(lambda, never()).invoke(anyString(), anyString(), any(byte[].class), any());
        verify(ses, never()).sendEmail(any());
    }

    @Test
    void aFunctionThatCannotBeInvokedFailsTheDelivery() {
        UserPool pool = pool(Map.of("KMSKeyID", keyArn, "CustomEmailSender", sender()), List.of());
        UserPoolClient client = client(pool);
        service.adminCreateUser(pool.getId(), "alice",
                Map.of("email", "alice@example.com", "email_verified", "true"), null);
        when(lambda.invoke(anyString(), eq(SENDER), any(byte[].class), eq(InvocationType.Event)))
                .thenThrow(new AwsException("ResourceNotFoundException", "Function not found: " + SENDER, 404));

        AwsException e = assertThrows(AwsException.class,
                () -> service.forgotPassword(client.getClientId(), "alice"));
        assertEquals("CodeDeliveryFailureException", e.getErrorCode());
    }

    private static Map<String, Object> sender() {
        return Map.of("LambdaArn", SENDER, "LambdaVersion", "V1_0");
    }

    private UserPool pool(Map<String, Object> lambdaConfig, List<String> autoVerifiedAttributes) {
        Map<String, Object> request = new HashMap<>();
        request.put("PoolName", "sender-pool");
        request.put("AutoVerifiedAttributes", autoVerifiedAttributes);
        request.put("LambdaConfig", lambdaConfig);
        return service.createUserPool(request, REGION);
    }

    private UserPoolClient client(UserPool pool) {
        return service.createUserPoolClient(pool.getId(), "app", false, false, List.of(), List.of(),
                null, List.of(), null, AUTH_FLOWS, null, null, List.of(), null, List.of(), null,
                null, null, List.of(), null, null);
    }

    @SuppressWarnings("unchecked")
    private String signIn(UserPoolClient client, String username, String password) {
        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", username, "PASSWORD", password));
        return (String) ((Map<String, Object>) result.get("AuthenticationResult")).get("AccessToken");
    }

    /** The event of the latest CustomEmailSender invocation, which must have been asynchronous. */
    private Map<String, Object> senderEvent() throws Exception {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambda, atLeastOnce())
                .invoke(eq(REGION), eq(SENDER), payload.capture(), eq(InvocationType.Event));
        return MAPPER.readValue(payload.getValue(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> request(Map<String, Object> event) {
        return (Map<String, Object>) event.get("request");
    }

    private DecryptedMessage decrypt(Map<String, Object> request) throws Exception {
        return EncryptionSdkReader.decrypt((String) request.get("code"), kms, REGION);
    }

    record DecryptedMessage(int algorithmSuiteId, Map<String, String> encryptionContext, String keyProviderId,
                            String keyProviderInfo, int signatureLength, String plaintext) {}

    /**
     * Reads an AWS Encryption SDK message (format version 1, framed, suite 0x0378) the way the
     * SDK's decrypt does: it unwraps the data key with KMS under the header's encryption context and
     * checks the header tag, the frame tag and the ECDSA P-384 signature over the whole message.
     */
    static final class EncryptionSdkReader {
        private EncryptionSdkReader() {}

        static DecryptedMessage decrypt(String base64Message, KmsService kms, String region) throws Exception {
            byte[] message = Base64.getDecoder().decode(base64Message);
            ByteBuffer in = ByteBuffer.wrap(message);
            assertEquals(1, in.get(), "version");
            assertEquals((byte) 0x80, in.get(), "type");
            int suiteId = Short.toUnsignedInt(in.getShort());
            byte[] messageId = bytes(in, 16);
            int contextLength = Short.toUnsignedInt(in.getShort());
            int contextEnd = in.position() + contextLength;
            Map<String, String> context = new LinkedHashMap<>();
            for (int pairs = Short.toUnsignedInt(in.getShort()); pairs > 0; pairs--) {
                context.put(text(in), text(in));
            }
            assertEquals(contextEnd, in.position(), "encryption context length");
            assertEquals(1, in.getShort(), "encrypted data keys");
            String providerId = text(in);
            String providerInfo = text(in);
            byte[] encryptedDataKey = bytes(in, Short.toUnsignedInt(in.getShort()));
            assertEquals(2, in.get(), "framed content");
            assertEquals(0, in.getInt(), "reserved");
            int ivLength = in.get();
            int frameLength = in.getInt();
            byte[] headerBody = Arrays.copyOf(message, in.position());
            byte[] headerIv = bytes(in, ivLength);
            byte[] headerTag = bytes(in, 16);

            byte[] dataKey = kms.decrypt(encryptedDataKey, context, region);
            SecretKeySpec key = deriveKey(dataKey, suiteId, messageId);
            open(key, headerIv, headerBody, headerTag);

            assertEquals(0xFFFFFFFF, in.getInt(), "final frame");
            int sequenceNumber = in.getInt();
            byte[] frameIv = bytes(in, ivLength);
            int contentLength = in.getInt();
            assertTrue(contentLength <= frameLength);
            byte[] sealed = bytes(in, contentLength + 16);
            byte[] frameAad = ByteBuffer.allocate(16 + 34 + 4 + 8)
                    .put(messageId)
                    .put("AWSKMSEncryptionClient Final Frame".getBytes(StandardCharsets.UTF_8))
                    .putInt(sequenceNumber)
                    .putLong(contentLength)
                    .array();
            byte[] plaintext = open(key, frameIv, frameAad, sealed);

            byte[] signed = Arrays.copyOf(message, in.position());
            byte[] signature = bytes(in, Short.toUnsignedInt(in.getShort()));
            assertFalse(in.hasRemaining(), "trailing bytes");
            Signature verifier = Signature.getInstance("SHA384withECDSA");
            verifier.initVerify(publicKey(context.get("aws-crypto-public-key")));
            verifier.update(signed);
            assertTrue(verifier.verify(signature), "message signature");

            return new DecryptedMessage(suiteId, context, providerId, providerInfo, signature.length,
                    new String(plaintext, StandardCharsets.UTF_8));
        }

        private static SecretKeySpec deriveKey(byte[] dataKey, int suiteId, byte[] messageId) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA384");
            mac.init(new SecretKeySpec(new byte[48], "HmacSHA384"));
            byte[] pseudoRandomKey = mac.doFinal(dataKey);
            mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA384"));
            mac.update(new byte[] {(byte) (suiteId >> 8), (byte) suiteId});
            mac.update(messageId);
            mac.update((byte) 1);
            return new SecretKeySpec(Arrays.copyOf(mac.doFinal(), 32), "AES");
        }

        private static byte[] open(SecretKeySpec key, byte[] iv, byte[] aad, byte[] sealed) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(sealed);
        }

        /** Decompresses the base64 P-384 point the SDK stores under {@code aws-crypto-public-key}. */
        private static PublicKey publicKey(String base64Point) throws Exception {
            byte[] point = Base64.getDecoder().decode(base64Point);
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp384r1"));
            ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
            BigInteger p = ((ECFieldFp) spec.getCurve().getField()).getP();
            BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, point.length));
            BigInteger rhs = x.pow(3).add(spec.getCurve().getA().multiply(x)).add(spec.getCurve().getB()).mod(p);
            BigInteger y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p);
            if (y.testBit(0) != (point[0] == 0x03)) {
                y = p.subtract(y);
            }
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
        }

        private static byte[] bytes(ByteBuffer in, int length) {
            byte[] value = new byte[length];
            in.get(value);
            return value;
        }

        private static String text(ByteBuffer in) {
            return new String(bytes(in, Short.toUnsignedInt(in.getShort())), StandardCharsets.UTF_8);
        }
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        }
    }
}
