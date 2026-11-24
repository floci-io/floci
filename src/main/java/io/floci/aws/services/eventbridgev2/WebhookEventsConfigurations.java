package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The request rules of a webhook event source that AWS checks after the constraints of its model, reported one at
 * a time. It also builds the form of the configuration that {@code DescribeEventSource} returns.
 */
final class WebhookEventsConfigurations {
    static final String WEBHOOK = "WebhookEventsConfiguration";
    static final String STATIC_TOKEN = "StaticTokenAuthConfiguration";
    static final String BASIC = "BasicHttpAuthConfiguration";
    static final String HMAC = "HmacAuthConfiguration";

    private static final String AUTH = "WebhookEventsConfiguration.AuthConfiguration";
    private static final String METADATA = "WebhookEventsConfiguration.MetadataHttpHeaders";
    private static final String HMAC_INVALID = AUTH + ".HmacAuthConfiguration is invalid: ";
    private static final String WHSEC = "whsec_";
    private static final int MIN_HMAC_SECRET_BYTES = 16;
    private static final String ONLY_WEBHOOK = "Only WebhookEventsConfiguration event sources are supported.";
    private static final List<String> SECRET_MEMBERS = List.of("SecretValue", "Username", "Password");
    private static final List<String> AUTH_MEMBERS = List.of(STATIC_TOKEN, BASIC, HMAC);

    private static final Set<String> REFUSED_COMMON_HEADERS = lowerCased("Host", "X-Forwarded-For",
            "X-Forwarded-Host", "X-Forwarded-Proto", "Connection", "Transfer-Encoding", "Cookie", "Via", "Upgrade",
            "TE", "Keep-Alive", "Forwarded", "Proxy-Authorization", "Trailer", "Proxy-Connection", "Max-Forwards");
    private static final Set<String> REFUSED_AUTH_HEADERS = withExtras(REFUSED_COMMON_HEADERS,
            "Content-Length", "Set-Cookie");
    private static final Set<String> REFUSED_METADATA_HEADERS = withExtras(REFUSED_COMMON_HEADERS, "Authorization");

    private WebhookEventsConfigurations() {
    }

    /**
     * Checks a create request's {@code Configuration} and returns its webhook configuration. A configuration of
     * another variant is refused, as Floci serves webhook event sources only.
     */
    static JsonNode validateForCreate(JsonNode configuration) {
        Map.Entry<String, JsonNode> variant = onlyVariant(configuration);
        if (!WEBHOOK.equals(variant.getKey())) {
            throw invalidInput(ONLY_WEBHOOK);
        }
        validateWebhook(variant.getValue());
        return variant.getValue();
    }

    /**
     * Checks an update request's {@code Configuration} against the authentication type the source was created
     * with, and returns its webhook configuration.
     */
    static JsonNode validateForUpdate(JsonNode configuration, String storedAuthType) {
        Map.Entry<String, JsonNode> variant = onlyVariant(configuration);
        if (!WEBHOOK.equals(variant.getKey())) {
            throw invalidInput("EventSource type is immutable: the configuration variant must remain "
                    + "WEBHOOK_EVENTS");
        }
        JsonNode webhook = variant.getValue();
        JsonNode auth = webhook.path("AuthConfiguration");
        for (String member : AUTH_MEMBERS) {
            for (String secret : SECRET_MEMBERS) {
                if (auth.path(member).hasNonNull(secret)) {
                    throw invalidInput("UpdateEventSource does not accept SecretValue, Username or Password; "
                            + "webhook credentials change only through RotateWebhookCredentials");
                }
            }
        }
        List<String> present = presentAuthMembers(auth);
        if (present.size() == 1 && !present.getFirst().equals(storedAuthType)) {
            throw invalidInput("The webhook authentication type can't be changed after the EventSource is created");
        }
        validateWebhook(webhook);
        return webhook;
    }

    /** How many variants a {@code Configuration} sets. */
    static int variantCount(JsonNode configuration) {
        return variants(configuration).size();
    }

    private static List<Map.Entry<String, JsonNode>> variants(JsonNode configuration) {
        List<Map.Entry<String, JsonNode>> members = new ArrayList<>();
        for (Map.Entry<String, JsonNode> member : configuration.properties()) {
            if (!member.getValue().isNull()) {
                members.add(member);
            }
        }
        return members;
    }

    private static Map.Entry<String, JsonNode> onlyVariant(JsonNode configuration) {
        List<Map.Entry<String, JsonNode>> members = variants(configuration);
        if (members.size() != 1) {
            throw invalidInput("Exactly one Configuration sub-field must be provided");
        }
        return members.getFirst();
    }

    private static List<String> presentAuthMembers(JsonNode auth) {
        List<String> present = new ArrayList<>();
        for (String member : AUTH_MEMBERS) {
            if (auth.hasNonNull(member)) {
                present.add(member);
            }
        }
        return present;
    }

    private static void validateWebhook(JsonNode webhook) {
        JsonNode auth = webhook.path("AuthConfiguration");
        List<String> present = presentAuthMembers(auth);
        if (present.size() != 1) {
            throw invalidInput(AUTH + " must set exactly one of StaticTokenAuthConfiguration, "
                    + "BasicHttpAuthConfiguration or HmacAuthConfiguration");
        }
        String authHeader;
        String authHeaderMember;
        switch (present.getFirst()) {
            case BASIC -> {
                validateBasic(auth.get(BASIC));
                authHeader = null;
                authHeaderMember = null;
            }
            case STATIC_TOKEN -> {
                JsonNode staticToken = auth.get(STATIC_TOKEN);
                refuseAuthHeader(STATIC_TOKEN, "TokenHttpHeader", staticToken.path("TokenHttpHeader").asText());
                authHeader = staticToken.path("TokenHttpHeader").asText();
                authHeaderMember = "StaticTokenAuthConfiguration.TokenHttpHeader";
            }
            default -> {
                JsonNode hmac = auth.get(HMAC);
                validateHmac(hmac);
                authHeader = hmac.path("SignatureHttpHeader").asText();
                authHeaderMember = "HmacAuthConfiguration.SignatureHttpHeader";
            }
        }
        validateMetadataHeaders(webhook.get("MetadataHttpHeaders"), authHeader, authHeaderMember);
    }

    private static void validateBasic(JsonNode basic) {
        if (basic.hasNonNull("Username") != basic.hasNonNull("Password")) {
            throw invalidInput(AUTH + ".BasicHttpAuthConfiguration must set both Username and Password, or neither. "
                    + "Supplying only one is not valid; omit both to have EventBridge generate the credential.");
        }
    }

    private static void validateHmac(JsonNode hmac) {
        String secret = hmacSecretValue(hmac);
        boolean base64 = "BASE64".equals(hmac.path("SecretEncoding").asText(null));
        int secretBytes = 0;
        if (secret != null) {
            if (base64) {
                try {
                    secretBytes = Base64.getDecoder().decode(secret).length;
                } catch (IllegalArgumentException e) {
                    throw invalidInput(AUTH + ".HmacAuthConfiguration.SecretValue must be standard Base64 when "
                            + "SecretEncoding is BASE64");
                }
            } else {
                secretBytes = secret.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        String signature = hmac.path("SignatureHttpHeader").asText(null);
        String timestamp = hmac.path("TimestampHttpHeader").asText(null);
        String messageId = hmac.path("MessageIdHttpHeader").asText(null);
        String composition = hmac.path("SignedMessageComposition").asText(null);
        boolean signsTimestamp = "TIMESTAMP_DOT_BODY".equals(composition)
                || "MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY".equals(composition);

        if (secret != null && secretBytes < MIN_HMAC_SECRET_BYTES) {
            throw hmacInvalid("SecretValue must be at least 16 bytes (for BASE64, after decoding)");
        }
        if (hmac.hasNonNull("TimestampToleranceInSeconds") && timestamp == null) {
            throw hmacInvalid("TimestampToleranceInSeconds requires TimestampHttpHeader to be set");
        }
        if (timestamp != null && !signsTimestamp) {
            throw hmacInvalid("TimestampHttpHeader and TimestampToleranceInSeconds require a "
                    + "SignedMessageComposition that signs the timestamp (TIMESTAMP_DOT_BODY or "
                    + "MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY)");
        }
        if (signsTimestamp && timestamp == null) {
            throw hmacInvalid(composition + " requires TimestampHttpHeader to be set");
        }
        if ("MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY".equals(composition) && messageId == null) {
            throw hmacInvalid("MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY requires MessageIdHttpHeader to be set");
        }
        if (timestamp != null && signature != null && signature.equalsIgnoreCase(timestamp)) {
            if (!hmac.hasNonNull("Delimiter")) {
                throw hmacInvalid("Delimiter is required when SignatureHttpHeader and TimestampHttpHeader are the "
                        + "same");
            }
            if (!hmac.hasNonNull("SignaturePrefix") || !hmac.hasNonNull("TimestampPrefix")) {
                throw hmacInvalid("SignaturePrefix and TimestampPrefix are both required when SignatureHttpHeader "
                        + "and TimestampHttpHeader are the same");
            }
        }
        refuseAuthHeader(HMAC, "SignatureHttpHeader", signature);
        refuseAuthHeader(HMAC, "TimestampHttpHeader", timestamp);
        refuseAuthHeader(HMAC, "MessageIdHttpHeader", messageId);
    }

    private static void refuseAuthHeader(String configuration, String member, String header) {
        if (header != null && REFUSED_AUTH_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
            throw invalidInput(AUTH + "." + configuration + "." + member + " can't be the header " + header
                    + ": it is not allowed. Header names are case-insensitive.");
        }
    }

    private static void validateMetadataHeaders(JsonNode headers, String authHeader, String authHeaderMember) {
        if (headers == null || !headers.isArray()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode node : headers) {
            String header = node.asText();
            String key = header.toLowerCase(Locale.ROOT);
            if (REFUSED_METADATA_HEADERS.contains(key)) {
                throw invalidInput(METADATA + " can't include the header " + header + ": it is not allowed");
            }
            if (authHeader != null && authHeader.equalsIgnoreCase(header)) {
                throw invalidInput(METADATA + " can't include the header " + header + " because it is "
                        + authHeaderMember);
            }
            if (!seen.add(key)) {
                throw invalidInput(METADATA + " lists the header " + header
                        + " more than once. Header names are case-insensitive.");
            }
        }
    }

    private static AwsException hmacInvalid(String reason) {
        return invalidInput(HMAC_INVALID + reason);
    }

    // ──────────────────────────── Forms of the configuration ────────────────────────────

    /**
     * The {@code SecretValue} of an HMAC configuration as stored and checked: with {@code SecretEncoding} BASE64,
     * the {@code whsec_} prefix signing tools put on a Base64 secret is removed. Null when none is given.
     */
    static String hmacSecretValue(JsonNode hmac) {
        JsonNode node = hmac.get("SecretValue");
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.textValue();
        return "BASE64".equals(hmac.path("SecretEncoding").asText(null)) && value.startsWith(WHSEC)
                ? value.substring(WHSEC.length()) : value;
    }

    /** The authentication type of a webhook configuration, the name of its one authentication member. */
    static String authType(JsonNode webhook) {
        List<String> present = presentAuthMembers(webhook.path("AuthConfiguration"));
        return present.isEmpty() ? null : present.getFirst();
    }

    /**
     * The {@code Configuration} a describe returns for a validated request: as given, less the members the model
     * does not declare and the members that hold secrets, and with no member that was sent as null.
     */
    static JsonNode echo(JsonNode webhook) {
        ObjectNode copy = (ObjectNode) EventBridgeV2RequestShapes.modeled("WebhookEventsSourceConfiguration", webhook);
        JsonNode auth = copy.path("AuthConfiguration");
        for (String member : AUTH_MEMBERS) {
            if (auth.get(member) instanceof ObjectNode configuration) {
                SECRET_MEMBERS.forEach(configuration::remove);
            }
        }
        dropNulls(copy);
        ObjectNode configuration = copy.objectNode();
        configuration.set(WEBHOOK, copy);
        return configuration;
    }

    private static void dropNulls(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isNull()) {
                    fields.remove();
                } else {
                    dropNulls(field.getValue());
                }
            }
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static Set<String> lowerCased(String... names) {
        Set<String> set = new HashSet<>();
        for (String name : names) {
            set.add(name.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(set);
    }

    private static Set<String> withExtras(Set<String> common, String... extras) {
        Set<String> set = new HashSet<>(common);
        set.addAll(lowerCased(extras));
        return Set.copyOf(set);
    }

    private static AwsException invalidInput(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }
}
