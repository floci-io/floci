package io.floci.aws.services.ec2instanceconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstanceConnectJsonHandlerTest {

    private static final String REGION = "us-east-1";
    private static final String INSTANCE = "i-0123456789abcdef0";
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final String ED25519 =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl user@host";

    private final ObjectMapper mapper = new ObjectMapper();
    private Ec2Service ec2;
    private InstanceConnectKeys keys;
    private InstanceConnectJsonHandler handler;
    private Instance instance;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        keys = mock(InstanceConnectKeys.class);
        handler = handlerIn("us-east-1");
        instance = new Instance();
        instance.setInstanceId(INSTANCE);
        instance.setState(InstanceState.running());
        when(ec2.getInstance(REGION, INSTANCE)).thenReturn(Optional.of(instance));
    }

    @Test
    void storesTheKeyForSixtySecondsAndReportsSuccess() {
        Response response = handler.handle("SendSSHPublicKey", request(INSTANCE, "admin", ED25519), REGION);

        assertEquals(200, response.getStatus());
        assertEquals(true, ((ObjectNode) response.getEntity()).get("Success").asBoolean());
        ArgumentCaptor<InstanceConnectKeys.ActiveKey> stored = ArgumentCaptor.forClass(InstanceConnectKeys.ActiveKey.class);
        verify(keys).add(stored.capture());
        assertEquals("admin", stored.getValue().osUser());
        assertEquals(NOW.plusSeconds(60), stored.getValue().expiresAt());
        assertEquals(((ObjectNode) response.getEntity()).get("RequestId").asText(), stored.getValue().requestId());
        assertEquals("arn:aws:iam::000000000000:root", stored.getValue().caller());
    }

    @Test
    void theCallerArnIsInTheDeploymentsPartition() {
        handlerIn("us-gov-west-1").handle("SendSSHPublicKey", request(INSTANCE, "admin", ED25519), REGION);

        ArgumentCaptor<InstanceConnectKeys.ActiveKey> stored = ArgumentCaptor.forClass(InstanceConnectKeys.ActiveKey.class);
        verify(keys).add(stored.capture());
        assertEquals("arn:aws-us-gov:iam::000000000000:root", stored.getValue().caller());
    }

    private InstanceConnectJsonHandler handlerIn(String defaultRegion) {
        return new InstanceConnectJsonHandler(ec2, keys, new RegionResolver(defaultRegion, "000000000000"), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rejectsWhatTheServiceModelAndAwsReject() {
        Map<ObjectNode, String> cases = new LinkedHashMap<>();
        cases.put(request("instance-1", "admin", ED25519), "InvalidArgsException");
        cases.put(request(INSTANCE, "bad user", ED25519), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", "ssh-ed25519 AAAA"), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", "ssh-dss " + "A".repeat(100)), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", rsaKey(1024)), "InvalidArgsException");
        // Right type, no key material: SSH could not use these, so they must not report Success.
        cases.put(request(INSTANCE, "admin", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5 " + "c".repeat(80)), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", sshKey("ssh-ed25519", new byte[31])), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", ecdsaKey("nistp256", "nistp384", 65, (byte) 4)), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", ecdsaKey("nistp256", "nistp256", 64, (byte) 4)), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", ecdsaKey("nistp256", "nistp256", 65, (byte) 2)), "InvalidArgsException");
        // An ssh-rsa blob with an empty exponent and a zero modulus, and a type label that disagrees with the blob.
        cases.put(request(INSTANCE, "admin", "ssh-rsa " + blob("ssh-rsa", new byte[0], new byte[256]) + " test"), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", "ssh-rsa " + blob("ssh-ed25519", new byte[32]) + " test"), "InvalidArgsException");
        // RFC 4251 s5: a 2048-bit modulus with its top bit set and no leading 0x00 is a negative mpint.
        byte[] unsignedModulus = new byte[256];
        unsignedModulus[0] = (byte) 0x80;
        cases.put(request(INSTANCE, "admin", "ssh-rsa " + blob("ssh-rsa", new byte[] {1, 0, 1}, unsignedModulus) + " test"), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", ED25519 + "\n#Timestamp=9999999999\n" + ED25519), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", ED25519.replace("user@host", "us\u0007er@host")), "InvalidArgsException");
        cases.put(request("i-0000000000000000f", "admin", ED25519), "EC2InstanceNotFoundException");
        cases.forEach((request, code) -> assertEquals(code, assertThrows(AwsException.class,
                () -> handler.handle("SendSSHPublicKey", request, REGION)).getErrorCode(), request.toString()));

        instance.setState(InstanceState.stopped());
        assertEquals("EC2InstanceStateInvalidException", assertThrows(AwsException.class,
                () -> handler.handle("SendSSHPublicKey", request(INSTANCE, "admin", ED25519), REGION)).getErrorCode());
        verify(keys, never()).add(any());
    }

    @Test
    void serialConsoleIsUnsupportedOnceTheInstanceIsFound() {
        ObjectNode request = request(INSTANCE, "admin", ED25519);
        assertEquals("SerialConsoleSessionUnsupportedException", assertThrows(AwsException.class,
                () -> handler.handle("SendSerialConsoleSSHPublicKey", request, REGION)).getErrorCode());
        ObjectNode missing = request("i-0000000000000000f", "admin", ED25519);
        assertEquals("EC2InstanceNotFoundException", assertThrows(AwsException.class,
                () -> handler.handle("SendSerialConsoleSSHPublicKey", missing, REGION)).getErrorCode());
    }

    @Test
    void acceptsAnRsaKeyOf2048Bits() {
        handler.handle("SendSSHPublicKey", request(INSTANCE, "admin", rsaKey(2048)), REGION);
        verify(keys).add(any());
    }

    @Test
    void acceptsWellFormedEd25519AndEcdsaKeys() {
        // ECDSA lines are real ssh-keygen -t ecdsa output (throwaway keys, public half only).
        handler.handle("SendSSHPublicKey", request(INSTANCE, "admin", sshKey("ssh-ed25519", new byte[32])), REGION);
        handler.handle("SendSSHPublicKey", request(INSTANCE, "admin", "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBKhKXIBq6uSFm4w/wDQuFN9HzZFxgqLbSpaTJB+J6aAHWXBU7rpF1xa7s4dpFzLTSb+xn8JbKWbLozKoeIWV8hw= test"), REGION);
        handler.handle("SendSSHPublicKey", request(INSTANCE, "admin", "ecdsa-sha2-nistp384 AAAAE2VjZHNhLXNoYTItbmlzdHAzODQAAAAIbmlzdHAzODQAAABhBJFY0hkoTsJID0TIS1BgpJVWTDguQ7sTojV4dfGqMEKjMSAoOnXUdGV1Y+AIhF4rZE9IKsHsjtxg4JYesYz7FR+9CTxHjog7BGzYmVcsVSnRL3t68fT7S3cXhoVkV/3WZA== test"), REGION);
        handler.handle("SendSSHPublicKey", request(INSTANCE, "admin", "ecdsa-sha2-nistp521 AAAAE2VjZHNhLXNoYTItbmlzdHA1MjEAAAAIbmlzdHA1MjEAAACFBABCp6D79VfVN1Td7nP/lhUWST6qIkN28rgBp1FhyKO2UCTwxObXTOEL7WvfyQ+/J60K21J1ovjtlcC61YhWx0F7eAC/glgVeIBd0leLmmv3YGJkmE7POLgg4nfVXNLJayW3Q8rgyNAROCIC++3Avdn9GZIPrrFnyo1M0aNeW03/dLE2Xg== test"), REGION);
        verify(keys, times(4)).add(any());
    }

    /** An ssh-ed25519 line carrying {@code material} as its key. */
    private static String sshKey(String type, byte[] material) {
        return type + " " + blob(type, material) + " test";
    }

    private static String ecdsaKey(String typeCurve, String blobCurve, int pointLength, byte first) {
        byte[] point = new byte[pointLength];
        point[0] = first;
        return "ecdsa-sha2-" + typeCurve + " " + blob("ecdsa-sha2-" + typeCurve, blobCurve.getBytes(StandardCharsets.US_ASCII), point)
                + " test";
    }

    private static String blob(String type, byte[]... fields) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        int size = 4 + t.length;
        for (byte[] f : fields) {
            size += 4 + f.length;
        }
        ByteBuffer b = ByteBuffer.allocate(size).putInt(t.length).put(t);
        for (byte[] f : fields) {
            b.putInt(f.length).put(f);
        }
        return Base64.getEncoder().encodeToString(b.array());
    }

    private ObjectNode request(String instanceId, String user, String key) {
        ObjectNode node = mapper.createObjectNode();
        node.put("InstanceId", instanceId);
        node.put("InstanceOSUser", user);
        node.put("SSHPublicKey", key);
        return node;
    }

    /** An ssh-rsa line whose modulus has exactly {@code bits} bits; only its size is ever checked. */
    private static String rsaKey(int bits) {
        byte[] type = "ssh-rsa".getBytes(StandardCharsets.US_ASCII);
        byte[] exponent = BigInteger.valueOf(65537).toByteArray();
        byte[] modulus = BigInteger.ONE.shiftLeft(bits - 1).toByteArray();
        ByteBuffer blob = ByteBuffer.allocate(12 + type.length + exponent.length + modulus.length);
        blob.putInt(type.length).put(type).putInt(exponent.length).put(exponent).putInt(modulus.length).put(modulus);
        return "ssh-rsa " + Base64.getEncoder().encodeToString(blob.array()) + " test";
    }
}
