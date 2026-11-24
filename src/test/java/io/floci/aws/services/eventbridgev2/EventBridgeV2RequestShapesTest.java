package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RequestConstraintCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The measured AWS answers for CreateEventSource, read through the vendored request shapes. */
class EventBridgeV2RequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/eventbridgev2-request-shapes.json", "EventBridge V2 request shapes",
            RequestConstraintCheck.standardMessages("InvalidInputException"));

    private static final String WEBHOOK = "configuration.webhookEventsConfiguration";

    @Test
    void emptyRequestNamesEveryRequiredMember() {
        assertEquals(List.of(
                "Value null at 'name' failed to satisfy constraint: Member must not be null",
                "Value null at 'eventBusArn' failed to satisfy constraint: Member must not be null",
                "Value null at 'configuration' failed to satisfy constraint: Member must not be null"),
                CHECK.violations("CreateEventSource", MAPPER.createObjectNode()));
    }

    @Test
    void descriptionWithAControlCharacterBreaksItsPattern() {
        ObjectNode request = validRequest();
        request.put("Description", "a\u0001b");

        assertEquals(List.of("Value 'a\u0001b' at 'description' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern: "
                + "[^\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]*(?![\\s\\S]).*"),
                CHECK.violations("CreateEventSource", request));
    }

    @Test
    void tagsBreakingTheirKeyAndValueShapesAreTwoViolations() {
        ObjectNode request = validRequest();
        request.putObject("Tags").put(" k", " v");

        assertEquals(List.of(
                "Value '{ k= v}' at 'tags' failed to satisfy constraint: Map keys must satisfy constraint: "
                        + "[Member must have length less than or equal to 128, "
                        + "Member must have length greater than or equal to 1, "
                        + "Member must satisfy regular expression pattern: \\S([\\s\\S]*\\S)?(?![\\s\\S]).*]",
                "Value '{ k= v}' at 'tags' failed to satisfy constraint: Map value must satisfy constraint: "
                        + "[Member must have length less than or equal to 256, "
                        + "Member must have length greater than or equal to 0, "
                        + "Member must satisfy regular expression pattern: (\\S([\\s\\S]*\\S)?)?(?![\\s\\S]).*, "
                        + "Member must not be null]"),
                CHECK.violations("CreateEventSource", request));
    }

    @Test
    void moreThanTwoHundredTagsIsOneSizeViolation() {
        ObjectNode request = validRequest();
        ObjectNode tags = request.putObject("Tags");
        for (int i = 0; i < 201; i++) {
            tags.put("k" + i, "v");
        }

        List<String> found = CHECK.violations("CreateEventSource", request);

        assertEquals(1, found.size());
        assertTrue(found.get(0).endsWith("' at 'tags' failed to satisfy constraint: "
                + "Member must have length less than or equal to 200"), found.get(0));
    }

    @Test
    void logLevelOutsideItsEnumListsTheValuesSorted() {
        ObjectNode request = validRequest();
        webhook(request).putObject("LogConfiguration").put("Level", "DEBUG");

        assertEquals(List.of("Value 'DEBUG' at '" + WEBHOOK + ".logConfiguration.level' failed to satisfy "
                + "constraint: Member must satisfy enum value set: [ERROR, INFO, OFF]"),
                CHECK.violations("CreateEventSource", request));
    }

    @Test
    void shortSecretLeavesItsValueOut() {
        ObjectNode request = validRequest();
        ObjectNode token = auth(request).putObject("StaticTokenAuthConfiguration");
        token.put("TokenHttpHeader", "x-token");
        token.put("SecretValue", "abc");

        assertEquals(List.of("Value at '" + WEBHOOK + ".authConfiguration.staticTokenAuthConfiguration.secretValue' "
                + "failed to satisfy constraint: Member must have length greater than or equal to 6"),
                CHECK.violations("CreateEventSource", request));
    }

    @Test
    void metadataHeaderBreakingItsShapeIsOneViolationForTheList() {
        ObjectNode request = validRequest();
        webhook(request).set("MetadataHttpHeaders", strings("ok", "bad header"));

        assertEquals(List.of("Value '[ok, bad header]' at '" + WEBHOOK + ".metadataHttpHeaders' failed to satisfy "
                + "constraint: Member must satisfy constraint: [Member must have length less than or equal to 128, "
                + "Member must have length greater than or equal to 1, "
                + "Member must satisfy regular expression pattern: [!#$%&'*+\\-.^_`|~0-9A-Za-z]+, "
                + "Member must not be null]"),
                CHECK.violations("CreateEventSource", request));
    }

    @Test
    void twentyOneMetadataHeadersAreOneSizeViolation() {
        ObjectNode request = validRequest();
        String[] names = new String[21];
        for (int i = 0; i < names.length; i++) {
            names[i] = "h" + (i + 1);
        }
        webhook(request).set("MetadataHttpHeaders", strings(names));

        assertEquals(List.of("Value '[" + String.join(", ", names) + "]' at '" + WEBHOOK
                + ".metadataHttpHeaders' failed to satisfy constraint: Member must have length less than or equal to 20"),
                CHECK.violations("CreateEventSource", request));
    }

    @Test
    void timestampToleranceOutsideItsRangeIsReportedWithItsValue() {
        String path = WEBHOOK + ".authConfiguration.hmacAuthConfiguration.timestampToleranceInSeconds";
        for (int value : new int[] {901, 0}) {
            ObjectNode request = validRequest();
            ObjectNode hmac = auth(request).putObject("HmacAuthConfiguration");
            hmac.put("Algorithm", "SHA256");
            hmac.put("Encoding", "HEX");
            hmac.put("SignatureHttpHeader", "x-signature");
            hmac.put("SignedMessageComposition", "BODY");
            hmac.put("TimestampToleranceInSeconds", value);

            String bound = value > 900
                    ? "less than or equal to 900"
                    : "greater than or equal to 1";
            assertEquals(List.of("Value '" + value + "' at '" + path + "' failed to satisfy constraint: "
                    + "Member must have value " + bound), CHECK.violations("CreateEventSource", request));
        }
    }

    @Test
    void longNameStartingWithASpaceBreaksLengthThenPattern() {
        ObjectNode request = validRequest();
        String name = " " + "a".repeat(256);
        request.put("Name", name);

        assertEquals(List.of(
                "Value '" + name + "' at 'name' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 256",
                "Value '" + name + "' at 'name' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: (?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*"),
                CHECK.violations("CreateEventSource", request));
    }

    @Test
    void validRequestHasNoViolations() {
        assertEquals(List.of(), CHECK.violations("CreateEventSource", validRequest()));
    }

    private static ObjectNode validRequest() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("Name", "source");
        request.put("EventBusArn", "arn:aws:events:us-east-1:123456789012:event-busv2/bus/abcdefghijklmnopqrstuvwxy");
        ObjectNode webhook = request.putObject("Configuration").putObject("WebhookEventsConfiguration");
        webhook.put("RoleArn", "arn:aws:iam::123456789012:role/role");
        webhook.putObject("AuthConfiguration");
        return request;
    }

    private static ObjectNode webhook(ObjectNode request) {
        return (ObjectNode) request.path("Configuration").path("WebhookEventsConfiguration");
    }

    private static ObjectNode auth(ObjectNode request) {
        return (ObjectNode) webhook(request).path("AuthConfiguration");
    }

    private static JsonNode strings(String... values) {
        ArrayNode array = MAPPER.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
