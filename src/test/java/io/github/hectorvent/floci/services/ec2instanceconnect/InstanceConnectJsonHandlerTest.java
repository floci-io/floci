package io.github.hectorvent.floci.services.ec2instanceconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
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
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getAccountId()).thenReturn("000000000000");
        handler = new InstanceConnectJsonHandler(ec2, keys, regions, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
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
    }

    @Test
    void rejectsWhatTheServiceModelAndAwsReject() {
        Map<ObjectNode, String> cases = new LinkedHashMap<>();
        cases.put(request("instance-1", "admin", ED25519), "InvalidArgsException");
        cases.put(request(INSTANCE, "bad user", ED25519), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", "ssh-ed25519 AAAA"), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", "ssh-dss " + "A".repeat(100)), "InvalidArgsException");
        cases.put(request(INSTANCE, "admin", rsaKey(1024)), "InvalidArgsException");
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
