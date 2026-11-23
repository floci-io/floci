package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.common.SmithyValidationErrors.Violations;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The request rules of a webhook event source: the constraints of the AWS model, reported together as AWS does,
 * and the rules AWS checks after them, reported one at a time. It also builds the form of the configuration that
 * {@code DescribeEventSource} returns.
 */
final class WebhookEventsConfigurations {
    static final String WEBHOOK = "WebhookEventsConfiguration";
    static final String STATIC_TOKEN = "StaticTokenAuthConfiguration";
    static final String BASIC = "BasicHttpAuthConfiguration";
    static final String HMAC = "HmacAuthConfiguration";

    private static final String ROOT = "configuration.webhookEventsConfiguration";
    private static final String AUTH = "WebhookEventsConfiguration.AuthConfiguration";
    private static final String METADATA = "WebhookEventsConfiguration.MetadataHttpHeaders";
    private static final String HMAC_INVALID = AUTH + ".HmacAuthConfiguration is invalid: ";
    private static final String WHSEC = "whsec_";
    private static final int MIN_HMAC_SECRET_BYTES = 16;
    private static final String ONLY_WEBHOOK = "Only WebhookEventsConfiguration event sources are supported.";
    private static final List<String> SECRET_MEMBERS = List.of("SecretValue", "Username", "Password");
    private static final List<String> AUTH_MEMBERS = List.of(STATIC_TOKEN, BASIC, HMAC);

    private static final String HEADER_NAME_PATTERN = "[!#$%&'*+\\-.^_`|~0-9A-Za-z]+";
    private static final String AFFIX_PATTERN = "[\\u0020-\\u007E]+";
    private static final StringRule NAME = new StringRule(1, 256, "(?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*", false);
    private static final StringRule EVENT_BUS_ARN = new StringRule(1, 1011,
            "arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:([0-9]{12}):event-busv2\\/" // partition-literal: AWS model text
                    + "[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}", false);
    private static final StringRule EVENT_SOURCE_ARN = new StringRule(1, 1011,
            "arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:([0-9]{12}):event-sourcev2\\/" // partition-literal: AWS model text
                    + "(aws\\.service|aws\\.partner|webhook)\\/[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}",
            false);
    private static final StringRule DESCRIPTION = new StringRule(0, 512,
            "[^\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]*(?![\\s\\S]).*", false);
    private static final StringRule CLIENT_TOKEN = new StringRule(1, 64, "[\\u0021-\\u007E]+", false);
    private static final StringRule TAG_KEY = new StringRule(1, 128, "\\S([\\s\\S]*\\S)?(?![\\s\\S]).*", false);
    private static final StringRule TAG_VALUE = new StringRule(0, 256, "(\\S([\\s\\S]*\\S)?)?(?![\\s\\S]).*",
            false);
    private static final StringRule KMS_KEY = new StringRule(0, 2048, "[a-zA-Z0-9_\\-/:]*", false);
    private static final StringRule ROLE_ARN = new StringRule(1, 1600,
            "arn:aws(-[a-z0-9]+)*:iam::\\d{12}:role\\/[\\w+=,.@/-]+", false); // partition-literal: AWS model text
    private static final StringRule HEADER_NAME = new StringRule(1, 512, HEADER_NAME_PATTERN, false);
    private static final StringRule METADATA_HEADER_NAME = new StringRule(1, 128, HEADER_NAME_PATTERN, false);
    private static final StringRule SECRET_VALUE = new StringRule(6, 512, null, true);
    private static final StringRule USERNAME = new StringRule(1, 512, "[^:\\s]+", false);
    private static final StringRule AFFIX = new StringRule(1, 64, AFFIX_PATTERN, false);
    private static final int MAX_TAGS = 200;
    private static final int MAX_METADATA_HEADERS = 20;
    private static final int MAX_TOLERANCE_SECONDS = 900;

    private static final List<String> HMAC_ALGORITHMS = List.of("SHA256", "SHA1");
    private static final List<String> HMAC_ENCODINGS = List.of("HEX", "BASE64");
    private static final List<String> COMPOSITIONS = List.of("BODY", "TIMESTAMP_DOT_BODY",
            "MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY");
    private static final List<String> SECRET_ENCODINGS = List.of("RAW", "BASE64");
    private static final List<String> DEDUPLICATION_TYPES = List.of("CONTENT_BASED");
    private static final List<String> LOG_LEVELS = List.of("OFF", "ERROR", "INFO");
    private static final List<String> INCLUDE_PAYLOADS = List.of("FULL", "ON_ERROR_ONLY");

    private static final Set<String> REFUSED_AUTH_HEADERS = lowerCased("Host", "X-Forwarded-For",
            "X-Forwarded-Host", "X-Forwarded-Proto", "Connection", "Content-Length", "Transfer-Encoding", "Cookie",
            "Set-Cookie", "Via", "Upgrade", "TE", "Keep-Alive", "Forwarded", "Proxy-Authorization", "Trailer",
            "Proxy-Connection", "Max-Forwards");
    private static final Set<String> REFUSED_METADATA_HEADERS = lowerCased("Authorization", "Proxy-Authorization",
            "Cookie", "Host", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "Connection",
            "Transfer-Encoding", "Via", "Forwarded", "TE", "Upgrade", "Keep-Alive", "Trailer", "Proxy-Connection",
            "Max-Forwards");

    /** The length and pattern a string member must satisfy, and whether AWS leaves its value out of a message. */
    private record StringRule(int min, int max, String pattern, boolean sensitive, Pattern compiled) {
        StringRule(int min, int max, String pattern, boolean sensitive) {
            this(min, max, pattern, sensitive, pattern == null ? null : Pattern.compile(pattern));
        }
    }

    private WebhookEventsConfigurations() {
    }

    // ──────────────────────────── Constraints ────────────────────────────

    /** The constraints a {@code CreateEventSource} request breaks, in the order of the model's members. */
    static List<String> createViolations(JsonNode request) {
        List<String> violations = new ArrayList<>();
        string(violations, request, "Name", "", NAME, true);
        string(violations, request, "EventBusArn", "", EVENT_BUS_ARN, true);
        configuration(violations, request, true);
        string(violations, request, "Description", "", DESCRIPTION, false);
        tags(violations, request);
        string(violations, request, "ClientToken", "", CLIENT_TOKEN, false);
        return violations;
    }

    /** The constraints an {@code UpdateEventSource} request breaks. */
    static List<String> updateViolations(JsonNode request) {
        List<String> violations = new ArrayList<>();
        string(violations, request, "EventSourceArn", "", EVENT_SOURCE_ARN, true);
        configuration(violations, request, false);
        string(violations, request, "Description", "", DESCRIPTION, false);
        return violations;
    }

    /** The constraints a request that names an event source only by its ARN breaks. */
    static List<String> eventSourceArnViolations(JsonNode request) {
        List<String> violations = new ArrayList<>();
        string(violations, request, "EventSourceArn", "", EVENT_SOURCE_ARN, true);
        return violations;
    }

    private static void configuration(List<String> violations, JsonNode request, boolean required) {
        JsonNode configuration = structure(request, "Configuration", "configuration");
        if (configuration == null) {
            if (required) {
                violations.add(SmithyValidationErrors.notNull("configuration"));
            }
            return;
        }
        JsonNode webhook = structure(configuration, WEBHOOK, ROOT);
        if (webhook == null) {
            return;
        }
        JsonNode auth = structure(webhook, "AuthConfiguration", ROOT + ".authConfiguration");
        if (auth == null) {
            violations.add(SmithyValidationErrors.notNull(ROOT + ".authConfiguration"));
        } else {
            authConfiguration(violations, auth);
        }
        string(violations, webhook, "KmsKeyIdentifier", ROOT, KMS_KEY, false);
        string(violations, webhook, "RoleArn", ROOT, ROLE_ARN, true);
        metadataHeaders(violations, webhook);
        JsonNode deduplication = structure(webhook, "DeduplicationConfiguration",
                ROOT + ".deduplicationConfiguration");
        if (deduplication != null) {
            enumeration(violations, deduplication, "DeduplicationType", ROOT + ".deduplicationConfiguration",
                    DEDUPLICATION_TYPES, true);
        }
        JsonNode log = structure(webhook, "LogConfiguration", ROOT + ".logConfiguration");
        if (log != null) {
            enumeration(violations, log, "Level", ROOT + ".logConfiguration", LOG_LEVELS, false);
            enumeration(violations, log, "IncludePayload", ROOT + ".logConfiguration", INCLUDE_PAYLOADS, false);
        }
    }

    private static void authConfiguration(List<String> violations, JsonNode auth) {
        String base = ROOT + ".authConfiguration";
        JsonNode staticToken = structure(auth, STATIC_TOKEN, base + "." + lower(STATIC_TOKEN));
        if (staticToken != null) {
            String path = base + "." + lower(STATIC_TOKEN);
            string(violations, staticToken, "TokenHttpHeader", path, HEADER_NAME, true);
            string(violations, staticToken, "SecretValue", path, SECRET_VALUE, false);
        }
        JsonNode basic = structure(auth, BASIC, base + "." + lower(BASIC));
        if (basic != null) {
            String path = base + "." + lower(BASIC);
            string(violations, basic, "Username", path, USERNAME, false);
            string(violations, basic, "Password", path, SECRET_VALUE, false);
        }
        JsonNode hmac = structure(auth, HMAC, base + "." + lower(HMAC));
        if (hmac != null) {
            hmacConfiguration(violations, hmac, base + "." + lower(HMAC));
        }
    }

    private static void hmacConfiguration(List<String> violations, JsonNode hmac, String path) {
        enumeration(violations, hmac, "Algorithm", path, HMAC_ALGORITHMS, true);
        enumeration(violations, hmac, "Encoding", path, HMAC_ENCODINGS, true);
        string(violations, hmac, "SignatureHttpHeader", path, HEADER_NAME, true);
        string(violations, hmac, "SignaturePrefix", path, AFFIX, false);
        string(violations, hmac, "Delimiter", path, AFFIX, false);
        string(violations, hmac, "TimestampHttpHeader", path, HEADER_NAME, false);
        string(violations, hmac, "TimestampPrefix", path, AFFIX, false);
        timestampTolerance(violations, hmac, path + ".timestampToleranceInSeconds");
        string(violations, hmac, "MessageIdHttpHeader", path, HEADER_NAME, false);
        enumeration(violations, hmac, "SignedMessageComposition", path, COMPOSITIONS, true);
        String secret = text(hmac, "SecretValue", path + ".secretValue");
        if (secret != null) {
            check(violations, path + ".secretValue", hmacSecretValue(hmac), SECRET_VALUE);
        }
        enumeration(violations, hmac, "SecretEncoding", path, SECRET_ENCODINGS, false);
    }

    private static void timestampTolerance(List<String> violations, JsonNode hmac, String path) {
        JsonNode node = hmac.get("TimestampToleranceInSeconds");
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isIntegralNumber()) {
            throw invalidInput(path + " must be an integer.");
        }
        if (!node.canConvertToLong()) {
            violations.add(node.bigIntegerValue().signum() < 0
                    ? SmithyValidationErrors.valueTooSmall(path, node.asText(), 1)
                    : SmithyValidationErrors.valueTooLarge(path, node.asText(), MAX_TOLERANCE_SECONDS));
            return;
        }
        String violation = SmithyValidationErrors.outOfRange(path, node.longValue(), 1, MAX_TOLERANCE_SECONDS);
        if (violation != null) {
            violations.add(violation);
        }
    }

    private static void metadataHeaders(List<String> violations, JsonNode webhook) {
        String path = ROOT + ".metadataHttpHeaders";
        JsonNode node = webhook.get("MetadataHttpHeaders");
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isArray()) {
            throw invalidInput(path + " must be a list.");
        }
        List<String> names = new ArrayList<>();
        boolean memberBroken = false;
        StringRule rule = METADATA_HEADER_NAME;
        for (JsonNode element : node) {
            if (element.isNull()) {
                names.add(null);
                memberBroken = true;
                continue;
            }
            if (!element.isTextual()) {
                throw invalidInput(path + " must be a list of strings.");
            }
            String name = element.textValue();
            names.add(name);
            int length = name.codePointCount(0, name.length());
            memberBroken |= length < rule.min() || length > rule.max() || !rule.compiled().matcher(name).matches();
        }
        if (names.size() > MAX_METADATA_HEADERS) {
            violations.add(Violations.QUOTED.constraint(path, names.toString(),
                    "Member must have length less than or equal to " + MAX_METADATA_HEADERS));
        }
        if (memberBroken) {
            List<String> rules = new ArrayList<>(SmithyValidationErrors.constraintSet(rule.min(), rule.max(),
                    rule.pattern(), null));
            rules.add("Member must not be null");
            violations.add(Violations.QUOTED.constraint(path, names.toString(),
                    "Member must satisfy constraint: " + rules));
        }
    }

    private static void tags(List<String> violations, JsonNode request) {
        JsonNode node = request.get("Tags");
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            throw invalidInput("tags must be a map of tag keys to values.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> tag : node.properties()) {
            if (!tag.getValue().isTextual()) {
                throw invalidInput("tags must be a map of tag keys to values.");
            }
            tags.put(tag.getKey(), tag.getValue().textValue());
        }
        String size = SmithyValidationErrors.sizeOutOfRange("tags", tags.size(), 0, MAX_TAGS);
        if (size != null) {
            violations.add(size);
        }
        boolean keyBroken = false;
        boolean valueBroken = false;
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            keyBroken |= !satisfies(tag.getKey(), TAG_KEY);
            valueBroken |= !satisfies(tag.getValue(), TAG_VALUE);
        }
        if (keyBroken) {
            violations.add(Violations.QUOTED.mapKeys("tags", tags.toString(), SmithyValidationErrors.constraintSet(
                    TAG_KEY.min(), TAG_KEY.max(), TAG_KEY.pattern(), null)));
        }
        if (valueBroken) {
            violations.add(Violations.QUOTED.constraint("tags", tags.toString(), "Map value must satisfy constraint: "
                    + SmithyValidationErrors.constraintSet(TAG_VALUE.min(), TAG_VALUE.max(), TAG_VALUE.pattern(),
                    null)));
        }
    }

    private static boolean satisfies(String value, StringRule rule) {
        int length = value.codePointCount(0, value.length());
        return length >= rule.min() && length <= rule.max() && rule.compiled().matcher(value).matches();
    }

    private static void string(List<String> violations, JsonNode parent, String member, String path,
                               StringRule rule, boolean required) {
        String memberPath = path.isEmpty() ? lower(member) : path + "." + lower(member);
        String value = text(parent, member, memberPath);
        if (value == null) {
            if (required) {
                violations.add(SmithyValidationErrors.notNull(memberPath));
            }
            return;
        }
        check(violations, memberPath, value, rule);
    }

    private static void check(List<String> violations, String path, String value, StringRule rule) {
        Violations form = rule.sensitive() ? Violations.WITHOUT_VALUES : Violations.QUOTED;
        String length = form.lengthOutOfRange(path, value, rule.min(), rule.max());
        if (length != null) {
            violations.add(length);
        }
        if (rule.pattern() != null && !rule.compiled().matcher(value).matches()) {
            violations.add(form.pattern(path, value, rule.pattern()));
        }
    }

    private static void enumeration(List<String> violations, JsonNode parent, String member, String path,
                                    List<String> allowed, boolean required) {
        String memberPath = path + "." + lower(member);
        String value = text(parent, member, memberPath);
        if (value == null) {
            if (required) {
                violations.add(SmithyValidationErrors.notNull(memberPath));
            }
            return;
        }
        if (!allowed.contains(value)) {
            violations.add(SmithyValidationErrors.notInEnum(value, memberPath, allowed.stream().sorted().toList()));
        }
    }

    private static String text(JsonNode parent, String member, String path) {
        JsonNode node = parent.get(member);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalidInput(path + " must be a string.");
        }
        return node.textValue();
    }

    private static JsonNode structure(JsonNode parent, String member, String path) {
        JsonNode node = parent.get(member);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw invalidInput(path + " must be a structure.");
        }
        return node;
    }

    // ──────────────────────────── Rules after the constraints ────────────────────────────

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

    private static Map.Entry<String, JsonNode> onlyVariant(JsonNode configuration) {
        List<Map.Entry<String, JsonNode>> members = new ArrayList<>();
        for (Map.Entry<String, JsonNode> member : configuration.properties()) {
            if (!member.getValue().isNull()) {
                members.add(member);
            }
        }
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
     * The {@code Configuration} a describe returns for a validated request: as given, less the members that hold
     * secrets, and with no member that was sent as null.
     */
    static JsonNode echo(JsonNode webhook) {
        ObjectNode copy = webhook.deepCopy();
        JsonNode auth = copy.get("AuthConfiguration");
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

    private static String lower(String member) {
        return Character.toLowerCase(member.charAt(0)) + member.substring(1);
    }

    private static AwsException invalidInput(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }
}
