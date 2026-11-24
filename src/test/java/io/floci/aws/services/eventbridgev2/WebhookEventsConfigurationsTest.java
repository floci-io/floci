package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookEventsConfigurationsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUS = "arn:aws:events:us-east-1:000000000000:event-busv2/orders/abcdefghijklmnopqrstuvwxy";
    private static final String ROLE = "arn:aws:iam::000000000000:role/Hook";
    private static final String PATH = "configuration.webhookEventsConfiguration";
    private static final String HMAC_PATH = PATH + ".authConfiguration.hmacAuthConfiguration";
    private static final String STATIC_AUTH = "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Token\"}}";
    private static final String HMAC_BASE = "\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\","
            + "\"SignatureHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\"";
    private static final String SIGNED_MESSAGE_ID_ERROR = "WebhookEventsConfiguration.AuthConfiguration"
            + ".HmacAuthConfiguration is invalid: ";

    // ──────────────────────────── Constraints, through the generated shapes ────────────────────────────

    @Test
    void validRequestHasNoViolations() {
        assertEquals(List.of(), violations("CreateEventSource", create("{\"Name\":\"hook\","
                + "\"EventBusArn\":\"" + BUS + "\",\"Description\":\"d\",\"ClientToken\":\"t\","
                + "\"Tags\":{\"a\":\"b\"},\"Configuration\":{\"WebhookEventsConfiguration\":{"
                + "\"AuthConfiguration\":" + STATIC_AUTH + ",\"RoleArn\":\"" + ROLE + "\"}}}")));
    }

    @Test
    void missingRequiredMembersAreReportedAsNull() {
        assertEquals(List.of(
                "Value null at 'name' failed to satisfy constraint: Member must not be null",
                "Value null at 'eventBusArn' failed to satisfy constraint: Member must not be null",
                "Value null at 'configuration' failed to satisfy constraint: Member must not be null"),
                violations("CreateEventSource", MAPPER.createObjectNode()));
        assertEquals(List.of(
                "Value null at '" + PATH + ".authConfiguration' failed to satisfy constraint: Member must not be null",
                "Value null at '" + PATH + ".roleArn' failed to satisfy constraint: Member must not be null"),
                webhookViolations("{}"));
    }

    @Test
    void nameBreakingItsPatternIsReportedWithTheModelsPattern() {
        List<String> violations = violations("CreateEventSource", create("{\"Name\":\"-x\","
                + "\"EventBusArn\":\"" + BUS + "\",\"Configuration\":{}}"));
        assertEquals(List.of("Value '-x' at 'name' failed to satisfy constraint: Member must satisfy regular "
                + "expression pattern: (?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*"), violations);
    }

    @Test
    void reservedAwsNamePrefixIsRefused() {
        List<String> violations = violations("CreateEventSource", create("{\"Name\":\"aws.x\","
                + "\"EventBusArn\":\"" + BUS + "\",\"Configuration\":{}}"));
        assertEquals(1, violations.size());
        assertTrue(violations.getFirst().startsWith("Value 'aws.x' at 'name'"), violations.toString());
    }

    @Test
    void severalViolationsAreCountedInOneMessageInMemberOrder() {
        List<String> violations = violations("CreateEventSource", create("{\"Name\":\"-x\","
                + "\"EventBusArn\":\"nope\",\"ClientToken\":\"\",\"Configuration\":{}}"));
        assertEquals(4, violations.size());
        assertTrue(violations.get(0).contains("at 'name'"));
        assertTrue(violations.get(1).contains("at 'eventBusArn' failed to satisfy constraint: Member must satisfy "
                + "regular expression pattern: arn:aws(-[a-z0-9]+)*:events:"), violations.get(1));
        assertEquals("Value '' at 'clientToken' failed to satisfy constraint: Member must have length greater than "
                + "or equal to 1", violations.get(2));
        assertTrue(violations.get(3).contains("at 'clientToken' failed to satisfy constraint: Member must satisfy "
                + "regular expression pattern: [\\u0021-\\u007E]+"));
    }

    @Test
    void timestampToleranceIsHeldTo1Through900() {
        assertEquals(List.of("Value '0' at '" + HMAC_PATH + ".timestampToleranceInSeconds' failed to satisfy "
                + "constraint: Member must have value greater than or equal to 1"),
                hmacViolations("\"TimestampToleranceInSeconds\":0"));
        assertEquals(List.of("Value '901' at '" + HMAC_PATH + ".timestampToleranceInSeconds' failed to satisfy "
                + "constraint: Member must have value less than or equal to 900"),
                hmacViolations("\"TimestampToleranceInSeconds\":901"));
        assertEquals(List.of(), hmacViolations("\"TimestampToleranceInSeconds\":900"));
    }

    @Test
    void enumValuesAreListedSorted() {
        assertEquals(List.of("Value 'DEBUG' at '" + PATH + ".logConfiguration.level' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [ERROR, INFO, OFF]"),
                webhookViolations("{\"AuthConfiguration\":" + STATIC_AUTH + ",\"RoleArn\":\"" + ROLE + "\","
                        + "\"LogConfiguration\":{\"Level\":\"DEBUG\"}}"));
        assertEquals(List.of("Value 'MD5' at '" + HMAC_PATH + ".algorithm' failed to satisfy constraint: Member "
                + "must satisfy enum value set: [SHA1, SHA256]"),
                hmacViolations("\"Algorithm\":\"MD5\""));
        assertEquals(List.of("Value 'X' at '" + PATH + ".deduplicationConfiguration.deduplicationType' failed to "
                + "satisfy constraint: Member must satisfy enum value set: [CONTENT_BASED]"),
                webhookViolations("{\"AuthConfiguration\":" + STATIC_AUTH + ",\"RoleArn\":\"" + ROLE + "\","
                        + "\"DeduplicationConfiguration\":{\"DeduplicationType\":\"X\"}}"));
    }

    @Test
    void sensitiveMembersLeaveTheirValueOutOfTheMessage() {
        assertEquals(List.of("Value at '" + PATH + ".authConfiguration.staticTokenAuthConfiguration.secretValue' "
                + "failed to satisfy constraint: Member must have length greater than or equal to 6"),
                webhookViolations("{\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":"
                        + "\"X-Token\",\"SecretValue\":\"abc\"}},\"RoleArn\":\"" + ROLE + "\"}"));
        assertEquals(List.of("Value at '" + PATH + ".authConfiguration.basicHttpAuthConfiguration.password' "
                + "failed to satisfy constraint: Member must have length greater than or equal to 6"),
                webhookViolations("{\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{\"Password\":\"abc\"}},"
                        + "\"RoleArn\":\"" + ROLE + "\"}"));
    }

    @Test
    void headerNameWithABadCharacterIsReportedWithItsPattern() {
        List<String> violations = webhookViolations("{\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":"
                + "{\"TokenHttpHeader\":\"X Token\"}},\"RoleArn\":\"" + ROLE + "\"}");
        assertEquals(List.of("Value 'X Token' at '" + PATH + ".authConfiguration.staticTokenAuthConfiguration"
                + ".tokenHttpHeader' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                + "[!#$%&'*+\\-.^_`|~0-9A-Za-z]+"), violations);
    }

    @Test
    void metadataHeaderBreakingItsMemberShapeReportsTheWholeList() {
        List<String> violations = webhookViolations("{\"AuthConfiguration\":" + STATIC_AUTH + ",\"RoleArn\":\""
                + ROLE + "\",\"MetadataHttpHeaders\":[\"X Y\"]}");
        assertEquals(List.of("Value '[X Y]' at '" + PATH + ".metadataHttpHeaders' failed to satisfy constraint: "
                + "Member must satisfy constraint: [Member must have length less than or equal to 128, Member must "
                + "have length greater than or equal to 1, Member must satisfy regular expression pattern: "
                + "[!#$%&'*+\\-.^_`|~0-9A-Za-z]+, Member must not be null]"), violations);
    }

    @Test
    void metadataHeaderListHoldsAtMost20() {
        StringBuilder headers = new StringBuilder();
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            headers.append(i == 0 ? "" : ",").append("\"X-H").append(i).append('"');
            rendered.append(i == 0 ? "" : ", ").append("X-H").append(i);
        }
        assertEquals(List.of("Value '[" + rendered + "]' at '" + PATH + ".metadataHttpHeaders' failed to satisfy "
                + "constraint: Member must have length less than or equal to 20"),
                webhookViolations("{\"AuthConfiguration\":" + STATIC_AUTH + ",\"RoleArn\":\"" + ROLE + "\","
                        + "\"MetadataHttpHeaders\":[" + headers + "]}"));
    }

    @Test
    void tagsAreHeldToTheirShape() {
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            rendered.append(i == 0 ? "" : ", ").append('k').append(i).append("=v");
        }
        assertEquals(List.of("Value '{" + rendered + "}' at 'tags' failed to satisfy constraint: Member must have "
                        + "length less than or equal to 200"),
                violations("CreateEventSource", create(withTags(201))));
        List<String> violations = violations("CreateEventSource", create("{\"Name\":\"hook\","
                + "\"EventBusArn\":\"" + BUS + "\",\"Configuration\":{\"WebhookEventsConfiguration\":{"
                + "\"AuthConfiguration\":" + STATIC_AUTH + ",\"RoleArn\":\"" + ROLE + "\"}},\"Tags\":{\" a\":\"b \"}}"));
        assertEquals(List.of(
                "Value '{ a=b }' at 'tags' failed to satisfy constraint: Map keys must satisfy constraint: "
                        + "[Member must have length less than or equal to 128, "
                        + "Member must have length greater than or equal to 1, "
                        + "Member must satisfy regular expression pattern: \\S([\\s\\S]*\\S)?(?![\\s\\S]).*]",
                "Value '{ a=b }' at 'tags' failed to satisfy constraint: Map value must satisfy constraint: "
                        + "[Member must have length less than or equal to 256, "
                        + "Member must have length greater than or equal to 0, "
                        + "Member must satisfy regular expression pattern: (\\S([\\s\\S]*\\S)?)?(?![\\s\\S]).*, "
                        + "Member must not be null]"), violations);
    }

    @Test
    void aValueOfTheWrongTypeIsAPlainInvalidInput() {
        AwsException e = assertThrows(AwsException.class, () -> EventBridgeV2RequestShapes.check(
                "CreateEventSource", create("{\"Name\":5}")));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void hmacSecretLengthIsCheckedAfterTheWhsecPrefixIsRemoved() {
        assertEquals(List.of(), hmacViolations("\"SecretEncoding\":\"BASE64\",\"SecretValue\":\"whsec_abcdef\""));
        assertEquals(1, hmacViolations("\"SecretEncoding\":\"BASE64\",\"SecretValue\":\"whsec_abc\"").size());
        assertEquals(List.of(), hmacViolations("\"SecretValue\":\"whsec_abc\""));
        JsonNode request = json("{\"Name\":\"hook\",\"EventBusArn\":\"" + BUS + "\",\"Configuration\":"
                + "{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":{\"HmacAuthConfiguration\":{" + HMAC_BASE
                + ",\"SecretEncoding\":\"BASE64\",\"SecretValue\":\"whsec_abcdef\"}},\"RoleArn\":\"" + ROLE + "\"}}}");
        EventBridgeV2RequestShapes.check("CreateEventSource", request);
        assertEquals("whsec_abcdef", request.at("/Configuration/WebhookEventsConfiguration/AuthConfiguration"
                + "/HmacAuthConfiguration/SecretValue").asText());
    }

    @Test
    void updateViolationsCoverTheArnConfigurationAndDescription() {
        List<String> violations = violations("UpdateEventSource", MAPPER.createObjectNode()
                .put("EventSourceArn", "x").put("Description", "a\nb"));
        assertEquals(2, violations.size());
        assertTrue(violations.get(0).startsWith("Value 'x' at 'eventSourceArn'"), violations.get(0));
        assertTrue(violations.get(1).contains("at 'description'"), violations.get(1));
    }

    // ──────────────────────────── Rules after the constraints ────────────────────────────

    @Test
    void configurationMustHoldExactlyOneMember() {
        assertEquals("Exactly one Configuration sub-field must be provided", createRefusal("{}"));
        assertEquals("Exactly one Configuration sub-field must be provided",
                createRefusal("{\"WebhookEventsConfiguration\":{},\"PartnerEventsConfiguration\":{}}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AwsServiceEventsConfiguration", "PartnerEventsConfiguration"})
    void otherVariantsAreNotSupported(String variant) {
        assertEquals("Only WebhookEventsConfiguration event sources are supported.",
                createRefusal("{\"" + variant + "\":{}}"));
    }

    @Test
    void authConfigurationMustSetExactlyOneMember() {
        String message = "WebhookEventsConfiguration.AuthConfiguration must set exactly one of "
                + "StaticTokenAuthConfiguration, BasicHttpAuthConfiguration or HmacAuthConfiguration";
        assertEquals(message, webhookRefusal("{}"));
        assertEquals(message, webhookRefusal("{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-T\"},"
                + "\"BasicHttpAuthConfiguration\":{}}"));
    }

    @Test
    void basicAuthNeedsBothCredentialsOrNeither() {
        String message = "WebhookEventsConfiguration.AuthConfiguration.BasicHttpAuthConfiguration must set both "
                + "Username and Password, or neither. Supplying only one is not valid; omit both to have "
                + "EventBridge generate the credential.";
        assertEquals(message, webhookRefusal("{\"BasicHttpAuthConfiguration\":{\"Username\":\"u\"}}"));
        assertEquals(message, webhookRefusal("{\"BasicHttpAuthConfiguration\":{\"Password\":\"secret1\"}}"));
        WebhookEventsConfigurations.validateForCreate(wrapped("{\"AuthConfiguration\":"
                + "{\"BasicHttpAuthConfiguration\":{}},\"RoleArn\":\"" + ROLE + "\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Host", "host", "X-Forwarded-For", "Content-Length", "Set-Cookie", "TE", "Trailer"})
    void staticTokenHeaderCannotBeAReservedName(String header) {
        assertEquals("WebhookEventsConfiguration.AuthConfiguration.StaticTokenAuthConfiguration.TokenHttpHeader "
                + "can't be the header " + header + ": it is not allowed. Header names are case-insensitive.",
                webhookRefusal("{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"" + header + "\"}}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "X-Forwarded-Port", "Content-Type", "Expect", "X-Custom"})
    void staticTokenHeaderMayBeAnyOtherName(String header) {
        WebhookEventsConfigurations.validateForCreate(wrapped("{\"AuthConfiguration\":"
                + "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"" + header + "\"}},\"RoleArn\":\""
                + ROLE + "\"}"));
    }

    @Test
    void hmacRulesAreCheckedInOrder() {
        assertEquals(SIGNED_MESSAGE_ID_ERROR + "SecretValue must be at least 16 bytes (for BASE64, after decoding)",
                hmacRefusal("\"SecretValue\":\"short-secret\""));
        assertEquals(SIGNED_MESSAGE_ID_ERROR + "SecretValue must be at least 16 bytes (for BASE64, after decoding)",
                hmacRefusal("\"SecretEncoding\":\"BASE64\",\"SecretValue\":\"c2hvcnQ=\",\"TimestampToleranceInSeconds\":5"));
        assertEquals(SIGNED_MESSAGE_ID_ERROR + "TimestampToleranceInSeconds requires TimestampHttpHeader to be set",
                hmacRefusal("\"TimestampToleranceInSeconds\":5"));
        assertEquals(SIGNED_MESSAGE_ID_ERROR + "TimestampHttpHeader and TimestampToleranceInSeconds require a "
                + "SignedMessageComposition that signs the timestamp (TIMESTAMP_DOT_BODY or "
                + "MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY)", hmacRefusal("\"TimestampHttpHeader\":\"X-Ts\""));
        assertEquals(SIGNED_MESSAGE_ID_ERROR + "TIMESTAMP_DOT_BODY requires TimestampHttpHeader to be set",
                hmacRefusal("\"SignedMessageComposition\":\"TIMESTAMP_DOT_BODY\""));
        assertEquals(SIGNED_MESSAGE_ID_ERROR + "MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY requires TimestampHttpHeader to "
                + "be set", hmacRefusal("\"SignedMessageComposition\":\"MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY\""));
        assertEquals(SIGNED_MESSAGE_ID_ERROR + "MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY requires MessageIdHttpHeader to "
                + "be set", hmacRefusal("\"SignedMessageComposition\":\"MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY\","
                + "\"TimestampHttpHeader\":\"X-Ts\""));
        assertEquals(SIGNED_MESSAGE_ID_ERROR + "Delimiter is required when SignatureHttpHeader and "
                + "TimestampHttpHeader are the same", hmacRefusal("\"SignedMessageComposition\":"
                + "\"TIMESTAMP_DOT_BODY\",\"TimestampHttpHeader\":\"x-sig\""));
        assertEquals(SIGNED_MESSAGE_ID_ERROR + "SignaturePrefix and TimestampPrefix are both required when "
                + "SignatureHttpHeader and TimestampHttpHeader are the same", hmacRefusal(
                "\"SignedMessageComposition\":\"TIMESTAMP_DOT_BODY\",\"TimestampHttpHeader\":\"X-SIG\","
                        + "\"Delimiter\":\",\",\"SignaturePrefix\":\"v1=\""));
    }

    @Test
    void hmacAcceptsTheCombinationsAwsAccepts() {
        for (String extra : List.of(
                "\"MessageIdHttpHeader\":\"X-Id\",\"Delimiter\":\",\",\"TimestampPrefix\":\"t=\"",
                "\"SignedMessageComposition\":\"TIMESTAMP_DOT_BODY\",\"TimestampHttpHeader\":\"X-SIG\","
                        + "\"Delimiter\":\",\",\"SignaturePrefix\":\"p=\",\"TimestampPrefix\":\"p=\"",
                "\"SignedMessageComposition\":\"MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY\",\"TimestampHttpHeader\":"
                        + "\"X-Ts\",\"MessageIdHttpHeader\":\"X-Sig\"")) {
            WebhookEventsConfigurations.validateForCreate(wrapped("{\"AuthConfiguration\":{\"HmacAuthConfiguration\":"
                    + "{" + withReplaced(extra) + "}},\"RoleArn\":\"" + ROLE + "\"}"));
        }
    }

    @Test
    void hmacBase64SecretMustBeStandardBase64() {
        assertEquals("WebhookEventsConfiguration.AuthConfiguration.HmacAuthConfiguration.SecretValue must be "
                + "standard Base64 when SecretEncoding is BASE64",
                hmacRefusal("\"SecretEncoding\":\"BASE64\",\"SecretValue\":\"abc-_abc-_abc-_abc-_abc-_\""));
        WebhookEventsConfigurations.validateForCreate(wrapped("{\"AuthConfiguration\":{\"HmacAuthConfiguration\":"
                + "{" + HMAC_BASE + ",\"SecretEncoding\":\"BASE64\",\"SecretValue\":\"whsec_"
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}},\"RoleArn\":\"" + ROLE + "\"}"));
    }

    @Test
    void hmacReservedHeadersAreCheckedAfterTheOtherRulesInOrder() {
        assertEquals("WebhookEventsConfiguration.AuthConfiguration.HmacAuthConfiguration.SignatureHttpHeader can't "
                + "be the header Cookie: it is not allowed. Header names are case-insensitive.",
                webhookRefusal("{\"HmacAuthConfiguration\":{" + HMAC_BASE.replace("X-Sig", "Cookie") + "}}"));
        assertEquals("WebhookEventsConfiguration.AuthConfiguration.HmacAuthConfiguration.TimestampHttpHeader can't "
                + "be the header Via: it is not allowed. Header names are case-insensitive.",
                hmacRefusal("\"SignedMessageComposition\":\"TIMESTAMP_DOT_BODY\",\"TimestampHttpHeader\":\"Via\""));
        assertEquals("WebhookEventsConfiguration.AuthConfiguration.HmacAuthConfiguration.MessageIdHttpHeader can't "
                + "be the header Host: it is not allowed. Header names are case-insensitive.",
                hmacRefusal("\"MessageIdHttpHeader\":\"Host\""));
    }

    @Test
    void metadataHeadersAreCheckedOneByOneInListOrder() {
        String prefix = "WebhookEventsConfiguration.MetadataHttpHeaders ";
        assertEquals(prefix + "can't include the header Authorization: it is not allowed",
                metadataRefusal("[\"X-Ok\",\"Authorization\"]"));
        assertEquals(prefix + "can't include the header x-token because it is StaticTokenAuthConfiguration"
                + ".TokenHttpHeader", metadataRefusal("[\"x-token\"]"));
        assertEquals(prefix + "lists the header x-A more than once. Header names are case-insensitive.",
                metadataRefusal("[\"X-A\",\"x-A\"]"));
        assertEquals(prefix + "lists the header X-A more than once. Header names are case-insensitive.",
                metadataRefusal("[\"X-A\",\"X-A\",\"Cookie\"]"));
    }

    @Test
    void metadataHeadersMayRepeatNamesAwsAllows() {
        WebhookEventsConfigurations.validateForCreate(wrapped("{\"AuthConfiguration\":" + STATIC_AUTH
                + ",\"RoleArn\":\"" + ROLE + "\",\"MetadataHttpHeaders\":[\"Content-Length\",\"Set-Cookie\","
                + "\"Content-Type\"]}"));
    }

    @Test
    void metadataHeaderCannotBeTheHmacSignatureHeader() {
        AwsException e = assertThrows(AwsException.class, () -> WebhookEventsConfigurations.validateForCreate(
                wrapped("{\"AuthConfiguration\":{\"HmacAuthConfiguration\":{" + HMAC_BASE + "}},\"RoleArn\":\"" + ROLE
                        + "\",\"MetadataHttpHeaders\":[\"x-SIG\"]}")));
        assertEquals("WebhookEventsConfiguration.MetadataHttpHeaders can't include the header x-SIG because it is "
                + "HmacAuthConfiguration.SignatureHttpHeader", e.getMessage());
    }

    @Test
    void updateRefusesAnotherVariantAnyCredentialAndAnotherAuthType() {
        assertEquals("EventSource type is immutable: the configuration variant must remain WEBHOOK_EVENTS",
                updateRefusal("{\"PartnerEventsConfiguration\":{}}", "StaticTokenAuthConfiguration"));
        assertEquals("UpdateEventSource does not accept SecretValue, Username or Password; webhook credentials "
                + "change only through RotateWebhookCredentials", updateRefusal("{\"WebhookEventsConfiguration\":"
                + "{\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{\"Username\":\"u\"}}}}",
                "BasicHttpAuthConfiguration"));
        assertEquals("The webhook authentication type can't be changed after the EventSource is created",
                updateRefusal("{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":"
                        + "{\"BasicHttpAuthConfiguration\":{}}}}", "StaticTokenAuthConfiguration"));
        assertEquals("Exactly one Configuration sub-field must be provided",
                updateRefusal("{}", "StaticTokenAuthConfiguration"));
    }

    @Test
    void updateKeepsApplyingTheRulesOfCreate() {
        assertEquals("WebhookEventsConfiguration.AuthConfiguration.StaticTokenAuthConfiguration.TokenHttpHeader "
                + "can't be the header Host: it is not allowed. Header names are case-insensitive.",
                updateRefusal("{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":"
                        + "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"Host\"}}}}",
                        "StaticTokenAuthConfiguration"));
    }

    // ──────────────────────────── Forms ────────────────────────────

    @Test
    void echoLeavesOutSecretsAndKeepsWhatTheRequestGave() {
        JsonNode webhook = config("{\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{\"Username\":\"u\","
                + "\"Password\":\"secret1\"}},\"RoleArn\":\"" + ROLE + "\",\"MetadataHttpHeaders\":[]}");
        assertEquals(MAPPER.createObjectNode().set("WebhookEventsConfiguration", config("{\"AuthConfiguration\":"
                + "{\"BasicHttpAuthConfiguration\":{}},\"RoleArn\":\"" + ROLE + "\",\"MetadataHttpHeaders\":[]}")),
                WebhookEventsConfigurations.echo(webhook));

        JsonNode hmac = config("{\"AuthConfiguration\":{\"HmacAuthConfiguration\":{" + HMAC_BASE
                + ",\"SecretValue\":\"0123456789abcdef\",\"SecretEncoding\":\"RAW\"}},\"RoleArn\":\"" + ROLE + "\"}");
        JsonNode echoed = WebhookEventsConfigurations.echo(hmac).path("WebhookEventsConfiguration")
                .path("AuthConfiguration").path("HmacAuthConfiguration");
        assertFalse(echoed.has("SecretValue"));
        assertEquals("RAW", echoed.path("SecretEncoding").asText());
        assertNull(WebhookEventsConfigurations.echo(hmac).path("WebhookEventsConfiguration").get("Description"));
    }

    @Test
    void authTypeNamesTheOneAuthenticationMember() {
        assertEquals("HmacAuthConfiguration", WebhookEventsConfigurations.authType(config(
                "{\"AuthConfiguration\":{\"HmacAuthConfiguration\":{}}}")));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static String withReplaced(String extra) {
        String base = HMAC_BASE;
        for (String member : List.of("SignedMessageComposition")) {
            if (extra.contains("\"" + member + "\"")) {
                base = base.replace(",\"SignedMessageComposition\":\"BODY\"", "");
            }
        }
        return base + "," + extra;
    }

    private static List<String> violations(String operation, JsonNode request) {
        try {
            EventBridgeV2RequestShapes.check(operation, request);
            return List.of();
        } catch (AwsException e) {
            return List.of(e.getMessage().replaceFirst("^\\d+ validation errors? detected: ", "").split("; "));
        }
    }

    private static JsonNode create(String json) {
        return json(json);
    }

    private static JsonNode json(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    private static JsonNode wrapped(String webhookJson) {
        return json("{\"WebhookEventsConfiguration\":" + webhookJson + "}");
    }

    private static JsonNode config(String webhookJson) {
        return json(webhookJson);
    }

    private static String withTags(int count) {
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < count; i++) {
            tags.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":\"v\"");
        }
        return "{\"Name\":\"hook\",\"EventBusArn\":\"" + BUS + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                + "{\"AuthConfiguration\":" + STATIC_AUTH + ",\"RoleArn\":\"" + ROLE + "\"}},\"Tags\":{" + tags + "}}";
    }

    private static List<String> webhookViolations(String webhookJson) {
        return violations("CreateEventSource", json("{\"Name\":\"hook\",\"EventBusArn\":\"" + BUS
                + "\",\"Configuration\":{\"WebhookEventsConfiguration\":" + webhookJson + "}}"));
    }

    private static List<String> hmacViolations(String extra) {
        String members = HMAC_BASE;
        if (extra.contains("\"Algorithm\"")) {
            members = members.replace("\"Algorithm\":\"SHA256\",", "");
        }
        return webhookViolations("{\"AuthConfiguration\":{\"HmacAuthConfiguration\":{" + members + "," + extra
                + "}},\"RoleArn\":\"" + ROLE + "\"}");
    }

    private static String createRefusal(String configurationJson) {
        return assertThrows(AwsException.class,
                () -> WebhookEventsConfigurations.validateForCreate(json(configurationJson))).getMessage();
    }

    private static String webhookRefusal(String authJson) {
        return createRefusal("{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":" + authJson + ",\"RoleArn\":\""
                + ROLE + "\"}}");
    }

    private static String hmacRefusal(String extra) {
        return webhookRefusal("{\"HmacAuthConfiguration\":{" + withReplaced(extra) + "}}");
    }

    private static String metadataRefusal(String headers) {
        return createRefusal("{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":" + STATIC_AUTH
                .replace("X-Token", "x-token") + ",\"RoleArn\":\"" + ROLE + "\",\"MetadataHttpHeaders\":" + headers
                + "}}");
    }

    private static String updateRefusal(String configurationJson, String storedAuthType) {
        return assertThrows(AwsException.class, () -> WebhookEventsConfigurations.validateForUpdate(
                json(configurationJson), storedAuthType)).getMessage();
    }
}
