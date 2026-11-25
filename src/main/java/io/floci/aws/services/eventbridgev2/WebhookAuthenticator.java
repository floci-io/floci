package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides whether a webhook request carries the credential its event source holds, by the rules of the source's
 * authentication type. Secrets and signatures are compared in constant time.
 */
final class WebhookAuthenticator {
    private static final Logger LOG = Logger.getLogger(WebhookAuthenticator.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EPOCH_SECONDS = Pattern.compile("[0-9]+");
    private static final String BASIC_SCHEME = "Basic ";
    private static final String MESSAGE_ID_COMPOSITION = "MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY";
    private static final String TIMESTAMP_COMPOSITION = "TIMESTAMP_DOT_BODY";
    // AWS holds a timestamp to this window when the source sets no TimestampToleranceInSeconds.
    private static final long DEFAULT_TOLERANCE_SECONDS = 300;

    private WebhookAuthenticator() {
    }

    /** {@code headers} are keyed by lower-case name. */
    static boolean allows(JsonNode webhook, String secret, Map<String, String> headers, byte[] body, Instant now) {
        JsonNode auth = webhook.path("AuthConfiguration");
        if (auth.hasNonNull(WebhookEventsConfigurations.STATIC_TOKEN)) {
            return staticToken(auth.get(WebhookEventsConfigurations.STATIC_TOKEN), secret, headers);
        }
        if (auth.hasNonNull(WebhookEventsConfigurations.BASIC)) {
            return basic(secret, headers);
        }
        if (auth.hasNonNull(WebhookEventsConfigurations.HMAC)) {
            return hmac(auth.get(WebhookEventsConfigurations.HMAC), secret, headers, body, now);
        }
        return false;
    }

    private static boolean staticToken(JsonNode configuration, String secret, Map<String, String> headers) {
        String value = headers.get(configuration.path("TokenHttpHeader").asText().toLowerCase(Locale.ROOT));
        return value != null && equal(value, secret);
    }

    private static boolean basic(String secret, Map<String, String> headers) {
        String value = headers.get("authorization");
        if (value == null || !value.regionMatches(true, 0, BASIC_SCHEME, 0, BASIC_SCHEME.length())) {
            return false;
        }
        String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(value.substring(BASIC_SCHEME.length())),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Not Base64, so it holds no credential.
            return false;
        }
        int colon = credentials.indexOf(':');
        if (colon < 0) {
            return false;
        }
        JsonNode stored;
        try {
            stored = JSON.readTree(secret);
        } catch (IOException ignored) {
            // Refused below as a secret without the members. The message may quote the secret, so it is not logged.
            stored = MissingNode.getInstance();
        }
        JsonNode storedUsername = stored.path("username");
        JsonNode storedPassword = stored.path("password");
        if (!storedUsername.isTextual() || !storedPassword.isTextual()) {
            LOG.warn("The secret of a webhook event source with Basic authentication holds no username and password");
            return false;
        }
        boolean user = equal(credentials.substring(0, colon), storedUsername.textValue());
        boolean password = equal(credentials.substring(colon + 1), storedPassword.textValue());
        return user & password;
    }

    private static boolean hmac(JsonNode configuration, String secret, Map<String, String> headers, byte[] body,
                                Instant now) {
        String signatureHeader = lowerCase(text(configuration, "SignatureHttpHeader"));
        String timestampHeader = lowerCase(text(configuration, "TimestampHttpHeader"));
        String delimiter = text(configuration, "Delimiter");
        String signaturePrefix = text(configuration, "SignaturePrefix");
        String timestampPrefix = text(configuration, "TimestampPrefix");
        String composition = configuration.path("SignedMessageComposition").asText("BODY");

        String signatureValue = headers.get(signatureHeader);
        if (signatureValue == null) {
            return false;
        }
        List<String> signatures = new ArrayList<>();
        String timestamp = null;
        if (timestampHeader != null && timestampHeader.equals(signatureHeader)) {
            for (String element : elements(signatureValue, delimiter)) {
                if (timestamp == null && timestampPrefix != null && element.startsWith(timestampPrefix)) {
                    timestamp = element.substring(timestampPrefix.length());
                } else if (signaturePrefix != null && element.startsWith(signaturePrefix)) {
                    signatures.add(element.substring(signaturePrefix.length()));
                }
            }
        } else {
            for (String element : elements(signatureValue, delimiter)) {
                if (signaturePrefix == null) {
                    signatures.add(element);
                } else if (element.startsWith(signaturePrefix)) {
                    signatures.add(element.substring(signaturePrefix.length()));
                }
            }
            if (timestampHeader != null) {
                timestamp = headers.get(timestampHeader);
                if (timestamp != null && timestampPrefix != null) {
                    timestamp = timestamp.startsWith(timestampPrefix)
                            ? timestamp.substring(timestampPrefix.length()) : null;
                }
            }
        }
        if (signatures.isEmpty() || (timestampHeader != null && !timestampAccepted(configuration, timestamp, now))) {
            return false;
        }

        ByteArrayOutputStream message = new ByteArrayOutputStream();
        if (MESSAGE_ID_COMPOSITION.equals(composition)) {
            String messageIdHeader = lowerCase(text(configuration, "MessageIdHttpHeader"));
            String messageId = messageIdHeader == null ? null : headers.get(messageIdHeader);
            if (messageId == null || messageId.indexOf('.') >= 0 || timestamp == null) {
                return false;
            }
            message.writeBytes((messageId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
        } else if (TIMESTAMP_COMPOSITION.equals(composition)) {
            if (timestamp == null) {
                return false;
            }
            message.writeBytes((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        }
        message.writeBytes(body);

        byte[] key = key(configuration, secret);
        if (key == null) {
            return false;
        }
        byte[] expected = mac(configuration.path("Algorithm").asText(), key, message.toByteArray());
        if (expected == null) {
            return false;
        }
        boolean base64 = "BASE64".equals(configuration.path("Encoding").asText());
        boolean matched = false;
        for (String signature : signatures) {
            byte[] decoded = decode(signature, base64);
            matched |= decoded != null && MessageDigest.isEqual(expected, decoded);
        }
        return matched;
    }

    private static List<String> elements(String value, String delimiter) {
        return delimiter == null || delimiter.isEmpty() ? List.of(value)
                : List.of(value.split(Pattern.quote(delimiter), -1));
    }

    private static boolean timestampAccepted(JsonNode configuration, String text, Instant now) {
        Instant sent = text == null ? null : instantOf(text);
        if (sent == null) {
            return false;
        }
        long tolerance = configuration.hasNonNull("TimestampToleranceInSeconds")
                ? configuration.get("TimestampToleranceInSeconds").asLong() : DEFAULT_TOLERANCE_SECONDS;
        try {
            return Duration.between(sent, now).abs().compareTo(Duration.ofSeconds(tolerance)) <= 0;
        } catch (ArithmeticException ignored) {
            // Too far from now to measure, so outside any tolerance.
            return false;
        }
    }

    /**
     * Epoch seconds or an ISO-8601 date and time with an offset. A count of milliseconds reads as seconds far ahead,
     * which the tolerance refuses.
     */
    private static Instant instantOf(String text) {
        try {
            if (EPOCH_SECONDS.matcher(text).matches()) {
                return Instant.ofEpochSecond(Long.parseLong(text));
            }
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeException | NumberFormatException ignored) {
            // Neither form, so not a time.
            return null;
        }
    }

    private static byte[] key(JsonNode configuration, String secret) {
        if (!"BASE64".equals(configuration.path("SecretEncoding").asText())) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            LOG.warnv("The secret of a webhook event source with SecretEncoding BASE64 is not Base64: {0}",
                    e.getMessage());
            return null;
        }
    }

    private static byte[] mac(String algorithm, byte[] key, byte[] message) {
        String name = "SHA1".equals(algorithm) ? "HmacSHA1" : "HmacSHA256";
        try {
            Mac mac = Mac.getInstance(name);
            mac.init(new SecretKeySpec(key, name));
            return mac.doFinal(message);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            LOG.warnv("The {0} of a webhook request could not be computed: {1}", name, e.getMessage());
            return null;
        }
    }

    private static byte[] decode(String signature, boolean base64) {
        try {
            return base64 ? Base64.getDecoder().decode(signature) : HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException ignored) {
            // Not in the encoding of the source, so it matches nothing.
            return null;
        }
    }

    private static String text(JsonNode node, String member) {
        return node.hasNonNull(member) ? node.get(member).asText() : null;
    }

    private static String lowerCase(String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    private static boolean equal(String given, String stored) {
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
    }
}
