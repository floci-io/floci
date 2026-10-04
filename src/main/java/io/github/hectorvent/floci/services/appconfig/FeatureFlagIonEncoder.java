package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes an {@code AWS.AppConfig.FeatureFlags} document as the Amazon Ion AWS AppConfigData returns to a caller that
 * accepts {@code application/ion;type=AWS.AppConfig.FeatureFlags}, as the AppConfig Agent does. Unlike the JSON
 * retrieval format it keeps each flag's variants and rules, for the caller to evaluate against its own context.
 *
 * <p>The layout is the one documented for the agent's local development mode
 * (https://docs.aws.amazon.com/appconfig/latest/userguide/appconfig-agent-how-to-use-local-development-samples.html):
 * each flag is a value annotated with its key. A basic flag is its retrieval-time JSON as a string; a flag with
 * variants is a list of its variants, each a list holding its rule as an s-expression and its content as a JSON
 * string, then its default's content alone. The binary stream, like AWS's, also has a local symbol table importing
 * AWS's shared {@code ops} and {@code anns} tables and annotates each variant with its name.
 * Only {@code (eq $attribute "value")} rules are encoded; {@link #encode} returns {@code null} for any other.
 */
final class FeatureFlagIonEncoder {

    static final String CONTENT_TYPE = "application/ion; type=AWS.AppConfig.FeatureFlags";

    private static final int SYSTEM_SYMBOLS = 9;
    private static final int OPS_MAX_ID = 23;
    private static final int ANNS_MAX_ID = 12;
    private static final int FIRST_LOCAL_SID = SYSTEM_SYMBOLS + OPS_MAX_ID + ANNS_MAX_ID + 1;
    /** The fourth symbol of {@code ops}, which follows the system symbols. */
    private static final int OPS_EQ_SID = SYSTEM_SYMBOLS + 4;

    private static final Pattern EQ_RULE =
            Pattern.compile("^\\s*\\(\\s*eq\\s+\\$([A-Za-z_][A-Za-z0-9_]*)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)\\s*$");

    private final Map<String, Integer> symbols = new LinkedHashMap<>();

    private FeatureFlagIonEncoder() {}

    /** Whether any flag in the document has variants, which is when AWS answers in Ion rather than JSON. */
    static boolean hasVariants(byte[] content, ObjectMapper objectMapper) {
        try {
            JsonNode values = objectMapper.readTree(content).get("values");
            if (values == null || !values.isObject()) {
                return false;
            }
            for (JsonNode flag : values) {
                JsonNode variants = flag.get("_variants");
                if (variants != null && variants.isArray() && !variants.isEmpty()) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** The Ion stream for {@code content}, or {@code null} if it is not a feature-flag document this can encode. */
    static byte[] encode(byte[] content, ObjectMapper objectMapper) {
        try {
            JsonNode document = objectMapper.readTree(content);
            JsonNode values = document == null ? null : document.get("values");
            if (values == null || !values.isObject()) {
                return null;
            }
            FeatureFlagIonEncoder encoder = new FeatureFlagIonEncoder();
            List<byte[]> flags = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> flag = fields.next();
                byte[] encoded = encoder.flag(flag.getKey(), flag.getValue(), objectMapper);
                if (encoded == null) {
                    return null;
                }
                flags.add(encoded);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] {(byte) 0xE0, 0x01, 0x00, (byte) 0xEA});
            out.write(encoder.symbolTable());
            for (byte[] flag : flags) {
                out.write(flag);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] flag(String key, JsonNode flag, ObjectMapper objectMapper) throws IOException {
        if (!flag.isObject()) {
            return null;
        }
        int keySid = intern(key);
        JsonNode variants = flag.get("_variants");
        if (variants == null || !variants.isArray() || variants.isEmpty()) {
            // A basic flag is its value in the JSON retrieval format, as a string.
            ObjectNode retrieval = AppConfigDataService.retrievalFlag(flag);
            return retrieval == null ? null : annotated(keySid, string(objectMapper.writeValueAsString(retrieval)));
        }
        ByteArrayOutputStream items = new ByteArrayOutputStream();
        String defaultJson = null;
        for (JsonNode variant : variants) {
            if (!variant.path("enabled").isBoolean()) {
                return null;
            }
            if (variant.hasNonNull("rule")) {
                byte[] encoded = variant(variant, objectMapper);
                if (encoded == null) {
                    return null;
                }
                items.write(encoded);
            } else {
                defaultJson = variantJson(variant.path("name").asText("default"), variant.get("enabled"),
                        variant.get("attributeValues"), objectMapper);
            }
        }
        if (defaultJson == null) {
            return null;
        }
        items.write(string(defaultJson));
        return annotated(keySid, container(0xB0, items.toByteArray()));
    }

    private byte[] variant(JsonNode variant, ObjectMapper objectMapper) throws IOException {
        String name = variant.path("name").asText(null);
        Matcher rule = EQ_RULE.matcher(variant.path("rule").asText(""));
        if (name == null || !rule.matches()) {
            return null;
        }
        int nameSid = intern(name);
        int attributeSid = intern("$" + rule.group(1));
        ByteArrayOutputStream sexp = new ByteArrayOutputStream();
        sexp.write(symbol(OPS_EQ_SID));
        sexp.write(symbol(attributeSid));
        sexp.write(string(objectMapper.readValue("\"" + rule.group(2) + "\"", String.class)));

        ByteArrayOutputStream items = new ByteArrayOutputStream();
        items.write(container(0xC0, sexp.toByteArray()));
        items.write(string(variantJson(name, variant.get("enabled"), variant.get("attributeValues"), objectMapper)));
        return annotated(nameSid, container(0xB0, items.toByteArray()));
    }

    /** A variant's attributes as the JSON string AWS puts in the stream: its name, whether it is on and, if it is, its attribute values. */
    private static String variantJson(String name, JsonNode enabled, JsonNode attributeValues, ObjectMapper objectMapper)
            throws IOException {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("_variant", name);
        json.set("enabled", enabled);
        // A variant that is off carries no attribute values, as in the JSON retrieval format.
        if (enabled.booleanValue() && attributeValues != null && attributeValues.isObject()) {
            attributeValues.fields().forEachRemaining(a -> json.set(a.getKey(), a.getValue()));
        }
        return objectMapper.writeValueAsString(json);
    }

    private int intern(String text) {
        return symbols.computeIfAbsent(text, t -> FIRST_LOCAL_SID + symbols.size());
    }

    private byte[] symbolTable() throws IOException {
        ByteArrayOutputStream imports = new ByteArrayOutputStream();
        imports.write(importOf("ops", OPS_MAX_ID));
        imports.write(importOf("anns", ANNS_MAX_ID));
        ByteArrayOutputStream local = new ByteArrayOutputStream();
        for (String symbol : symbols.keySet()) {
            local.write(string(symbol));
        }
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        table.write(varUInt(6));
        table.write(container(0xB0, imports.toByteArray()));
        table.write(varUInt(7));
        table.write(container(0xB0, local.toByteArray()));
        return annotated(3, container(0xD0, table.toByteArray()));
    }

    private static byte[] importOf(String name, int maxId) throws IOException {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        fields.write(varUInt(4));
        fields.write(string(name));
        fields.write(varUInt(5));
        fields.write(uint(1));
        fields.write(varUInt(8));
        fields.write(uint(maxId));
        return container(0xD0, fields.toByteArray());
    }

    private static byte[] annotated(int annotationSid, byte[] value) throws IOException {
        byte[] annotation = varUInt(annotationSid);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(varUInt(annotation.length));
        body.write(annotation);
        body.write(value);
        return container(0xE0, body.toByteArray());
    }

    private static byte[] string(String text) throws IOException {
        return container(0x80, text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] symbol(int sid) throws IOException {
        return container(0x70, unsigned(sid));
    }

    private static byte[] uint(int value) throws IOException {
        return container(0x20, unsigned(value));
    }

    /** The length goes in the low nibble, or after the type byte from 14 up. */
    private static byte[] container(int typeNibble, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (body.length < 14) {
            out.write(typeNibble | body.length);
        } else {
            out.write(typeNibble | 0x0E);
            out.write(varUInt(body.length));
        }
        out.write(body);
        return out.toByteArray();
    }

    private static byte[] unsigned(int value) {
        int length = value < 0x100 ? 1 : value < 0x10000 ? 2 : value < 0x1000000 ? 3 : 4;
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[length - 1 - i] = (byte) (value >>> (8 * i));
        }
        return out;
    }

    private static byte[] varUInt(int value) {
        int groups = 1;
        while ((value >>> (7 * groups)) != 0) {
            groups++;
        }
        byte[] out = new byte[groups];
        for (int i = 0; i < groups; i++) {
            out[i] = (byte) ((value >>> (7 * (groups - 1 - i))) & 0x7F);
        }
        out[groups - 1] |= (byte) 0x80;
        return out;
    }
}
