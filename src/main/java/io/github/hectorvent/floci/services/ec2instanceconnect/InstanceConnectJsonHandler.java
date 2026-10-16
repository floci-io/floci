package io.github.hectorvent.floci.services.ec2instanceconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * EC2 Instance Connect ({@code X-Amz-Target: AWSEC2InstanceConnectService.*}).
 *
 * <p>{@code SendSSHPublicKey} validates the request against the service model and the instance's
 * state, then hands the key to {@link InstanceConnectKeys}, which serves it signed to the
 * instance's agent through IMDS for {@link InstanceConnectKeys#KEY_LIFETIME}. The serial console
 * operation is answered with {@code SerialConsoleSessionUnsupportedException} once the instance is
 * found: Docker-backed instances have no serial console.
 */
@ApplicationScoped
public class InstanceConnectJsonHandler {

    // Patterns and bounds from the service model (ec2-instance-connect 2018-04-02).
    private static final Pattern INSTANCE_ID = Pattern.compile("^i-[a-f0-9]+$");
    private static final Pattern OS_USER = Pattern.compile(
            "(^[A-Za-z_][A-Za-z0-9@._-]{0,30}[A-Za-z0-9$_-]?$)"
                    + "|(^(?=.{2,32}$)[0-9]{1,32}[A-Za-z@._-][A-Za-z0-9@._-]*[A-Za-z0-9$_-]?$)");
    private static final Pattern AVAILABILITY_ZONE = Pattern.compile("^(\\w+-){2,3}\\d+\\w+$");
    /** The key types AWS documents for EC2 Instance Connect: RSA (2048 bits or more), ED25519 and ECDSA. */
    private static final Set<String> KEY_TYPES = Set.of("ssh-rsa", "ssh-ed25519",
            "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521");
    private static final int MIN_RSA_BITS = 2048;

    private final Ec2Service ec2Service;
    private final InstanceConnectKeys keys;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Inject
    public InstanceConnectJsonHandler(Ec2Service ec2Service, InstanceConnectKeys keys,
                                      RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(ec2Service, keys, regionResolver, objectMapper, Clock.systemUTC());
    }

    InstanceConnectJsonHandler(Ec2Service ec2Service, InstanceConnectKeys keys, RegionResolver regionResolver,
                               ObjectMapper objectMapper, Clock clock) {
        this.ec2Service = ec2Service;
        this.keys = keys;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "SendSSHPublicKey" -> sendSshPublicKey(request, region);
            case "SendSerialConsoleSSHPublicKey" -> sendSerialConsoleSshPublicKey(request, region);
            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported.", 400);
        };
    }

    private Response sendSshPublicKey(JsonNode request, String region) {
        String instanceId = required(request, "InstanceId");
        String osUser = required(request, "InstanceOSUser");
        String publicKey = required(request, "SSHPublicKey");
        String zone = request.path("AvailabilityZone").asText(null);

        if (instanceId.length() < 10 || instanceId.length() > 32 || !INSTANCE_ID.matcher(instanceId).matches()) {
            throw invalid("Invalid instance ID: " + instanceId);
        }
        if (osUser.length() > 32 || !OS_USER.matcher(osUser).matches()) {
            throw invalid("Invalid InstanceOSUser: " + osUser);
        }
        if (publicKey.length() < 80 || publicKey.length() > 4096) {
            throw invalid("SSHPublicKey must be between 80 and 4096 characters long.");
        }
        if (publicKey.trim().chars().anyMatch(Character::isISOControl)) {
            // The key is copied verbatim into the signed active-keys document, whose framing is
            // line-based: an embedded newline would let one key forge lines for another.
            throw invalid("SSHPublicKey must be a single line without control characters.");
        }
        validatePublicKey(publicKey.trim());
        if (zone != null && (zone.length() < 6 || zone.length() > 32 || !AVAILABILITY_ZONE.matcher(zone).matches())) {
            throw invalid("Invalid AvailabilityZone: " + zone);
        }

        Instance instance = ec2Service.getInstance(region, instanceId)
                .orElseThrow(() -> new AwsException("EC2InstanceNotFoundException",
                        "Instance not found: " + instanceId, 400));
        String instanceZone = instance.getPlacement() != null ? instance.getPlacement().getAvailabilityZone() : null;
        if (zone != null && instanceZone != null && !zone.equals(instanceZone)) {
            throw new AwsException("EC2InstanceNotFoundException",
                    "Instance not found in availability zone " + zone + ": " + instanceId, 400);
        }
        String state = instance.getState() != null ? instance.getState().getName() : null;
        if (!"running".equals(state)) {
            throw new AwsException("EC2InstanceStateInvalidException",
                    "Instance " + instanceId + " is not in a valid state for this operation: " + state, 400);
        }

        String requestId = UUID.randomUUID().toString();
        String caller = "arn:aws:iam::" + regionResolver.getAccountId() + ":root";
        keys.add(new InstanceConnectKeys.ActiveKey(instanceId, osUser, publicKey.trim(),
                clock.instant().plus(InstanceConnectKeys.KEY_LIFETIME), caller, requestId));

        ObjectNode response = objectMapper.createObjectNode();
        response.put("RequestId", requestId);
        response.put("Success", true);
        return Response.ok(response).build();
    }

    /** Docker-backed instances have no serial console; AWS checks the instance exists before saying so. */
    private Response sendSerialConsoleSshPublicKey(JsonNode request, String region) {
        String instanceId = required(request, "InstanceId");
        ec2Service.getInstance(region, instanceId)
                .orElseThrow(() -> new AwsException("EC2InstanceNotFoundException",
                        "Instance not found: " + instanceId, 400));
        throw new AwsException("SerialConsoleSessionUnsupportedException",
                "The EC2 serial console is not supported for instance " + instanceId + ".", 400);
    }

    private static String required(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw invalid(field + " is required.");
        }
        return value.asText();
    }

    /** One OpenSSH public key line: a supported type, its base64 blob carrying the same type, RSA of 2048 bits or more. */
    static void validatePublicKey(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length < 2 || !KEY_TYPES.contains(parts[0])) {
            throw invalid("Unsupported or malformed SSH public key. Supported types: RSA, ED25519 and ECDSA.");
        }
        try {
            ByteBuffer blob = ByteBuffer.wrap(Base64.getDecoder().decode(parts[1]));
            String type = new String(readString(blob), StandardCharsets.US_ASCII);
            if (!type.equals(parts[0])) {
                throw invalid("The SSH public key's type does not match its encoded data.");
            }
            if ("ssh-rsa".equals(type)) {
                readString(blob); // public exponent
                int bits = new BigInteger(1, readString(blob)).bitLength();
                if (bits < MIN_RSA_BITS) {
                    throw invalid("RSA keys must be at least " + MIN_RSA_BITS + " bits long.");
                }
            }
        } catch (IllegalArgumentException | BufferUnderflowException e) {
            throw invalid("Malformed SSH public key.");
        }
    }

    private static byte[] readString(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            throw new IllegalArgumentException("bad length");
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidArgsException", message, 400);
    }
}
