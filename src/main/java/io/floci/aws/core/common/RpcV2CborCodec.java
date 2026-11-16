package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;

/** Converts only members whose wire types are identified by the vendored operation model. */
final class RpcV2CborCodec {

    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private final JsonNode metadata;

    private RpcV2CborCodec(JsonNode metadata) {
        this.metadata = metadata.deepCopy();
    }

    static RpcV2CborCodec load() {
        return VendoredResources.read("aws/rpcv2-cbor-shapes.json", "RPC v2 CBOR wire model",
                in -> parse(new ObjectMapper().readTree(in)));
    }

    static RpcV2CborCodec parse(JsonNode metadata) {
        return new RpcV2CborCodec(metadata);
    }

    boolean supports(String serviceId) {
        return metadata.path("aliases").has(serviceId);
    }

    boolean hasOutput(String serviceId, String operation) {
        return service(serviceId).path("operations").path(operation).path("hasOutput").asBoolean(true);
    }

    JsonNode decodeRequest(String serviceId, String operation, JsonNode body) {
        JsonNode service = service(serviceId);
        String root = service.path("operations").path(operation).path("input").asText(null);
        return decode(service, root, body, null);
    }

    byte[] encodeResponse(String serviceId, String operation, JsonNode body) throws IOException {
        JsonNode service = service(serviceId);
        String root = service.path("operations").path(operation).path("output").asText(null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (CBORGenerator generator = (CBORGenerator) CBOR.getFactory().createGenerator(bytes)) {
            encode(generator, service, root, body, null);
        }
        return bytes.toByteArray();
    }

    private JsonNode service(String serviceId) {
        String name = metadata.path("aliases").path(serviceId).asText("");
        return metadata.path("services").path(name);
    }

    private JsonNode decode(JsonNode service, String name, JsonNode value, String formatOverride) {
        if (value == null || value.isNull()) {
            return value;
        }
        JsonNode shape = shape(service, name);
        return switch (shape.path("type").asText()) {
            case "blob" -> {
                if (!value.isBinary()) {
                    throw serialization("A CBOR blob must be a byte string");
                }
                try {
                    yield TextNode.valueOf(Base64.getEncoder().encodeToString(value.binaryValue()));
                } catch (IOException e) {
                    throw serialization("Could not decode a CBOR byte string");
                }
            }
            case "timestamp" -> {
                if (!value.isNumber()) {
                    throw serialization("A CBOR timestamp must contain epoch seconds");
                }
                String format = timestampFormat(service, shape, formatOverride);
                yield switch (format) {
                    case "iso8601" -> TextNode.valueOf(instant(value.decimalValue()).toString());
                    case "rfc822" -> TextNode.valueOf(DateTimeFormatter.RFC_1123_DATE_TIME
                            .format(instant(value.decimalValue()).atZone(ZoneOffset.UTC)));
                    default -> value;
                };
            }
            case "structure" -> {
                if (!value.isObject()) {
                    yield value.deepCopy();
                }
                ObjectNode result = value.deepCopy();
                for (Map.Entry<String, JsonNode> member : shape.path("members").properties()) {
                    JsonNode child = value.get(member.getKey());
                    if (child == null) {
                        continue;
                    }
                    JsonNode spec = member.getValue();
                    String jsonName = spec.path("jsonName").asText(member.getKey());
                    result.remove(member.getKey());
                    result.set(jsonName, decode(service, spec.path("shape").asText(null), child,
                            spec.path("timestampFormat").asText(null)));
                }
                yield result;
            }
            case "list" -> {
                if (!value.isArray()) {
                    yield value.deepCopy();
                }
                ArrayNode result = CBOR.createArrayNode();
                for (JsonNode child : value) {
                    result.add(decode(service, shape.path("member").asText(null), child, formatOverride));
                }
                yield result;
            }
            case "map" -> {
                if (!value.isObject()) {
                    yield value.deepCopy();
                }
                ObjectNode result = CBOR.createObjectNode();
                for (Map.Entry<String, JsonNode> entry : value.properties()) {
                    result.set(entry.getKey(), decode(service, shape.path("value").asText(null), entry.getValue(),
                            formatOverride));
                }
                yield result;
            }
            default -> value.deepCopy();
        };
    }

    private void encode(CBORGenerator generator, JsonNode service, String name, JsonNode value,
            String formatOverride) throws IOException {
        JsonNode shape = shape(service, name);
        if (value == null || value.isNull()) {
            generator.writeNull();
        } else if ("blob".equals(shape.path("type").asText())) {
            generator.writeBinary(value.isBinary() ? value.binaryValue() : Base64.getDecoder().decode(value.asText()));
        } else if ("timestamp".equals(shape.path("type").asText())) {
            BigDecimal seconds;
            if (value.isNumber()) {
                seconds = value.decimalValue();
            } else {
                Instant timestamp = "rfc822".equals(timestampFormat(service, shape, formatOverride))
                        ? ZonedDateTime.parse(value.asText(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                        : Instant.parse(value.asText());
                seconds = BigDecimal.valueOf(timestamp.getEpochSecond())
                        .add(BigDecimal.valueOf(timestamp.getNano(), 9));
            }
            generator.writeTag(1);
            if (seconds.stripTrailingZeros().scale() <= 0) {
                generator.writeNumber(seconds.longValueExact());
            } else {
                generator.writeNumber(seconds.doubleValue());
            }
        } else if ("structure".equals(shape.path("type").asText()) && value.isObject()) {
            generator.writeStartObject();
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                Map.Entry<String, JsonNode> member = jsonMember(shape, field.getKey());
                generator.writeFieldName(member == null ? field.getKey() : member.getKey());
                JsonNode spec = member == null ? CBOR.nullNode() : member.getValue();
                encode(generator, service, spec.path("shape").asText(null), field.getValue(),
                        spec.path("timestampFormat").asText(null));
            }
            generator.writeEndObject();
        } else if ("list".equals(shape.path("type").asText()) && value.isArray()) {
            generator.writeStartArray();
            for (JsonNode child : value) {
                encode(generator, service, shape.path("member").asText(null), child, formatOverride);
            }
            generator.writeEndArray();
        } else if ("map".equals(shape.path("type").asText()) && value.isObject()) {
            generator.writeStartObject();
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                generator.writeFieldName(field.getKey());
                encode(generator, service, shape.path("value").asText(null), field.getValue(), formatOverride);
            }
            generator.writeEndObject();
        } else {
            generator.writeTree(value);
        }
    }

    private static JsonNode shape(JsonNode service, String name) {
        return service.path("shapes").path(name == null ? "" : name);
    }

    private static Map.Entry<String, JsonNode> jsonMember(JsonNode shape, String jsonName) {
        for (Map.Entry<String, JsonNode> member : shape.path("members").properties()) {
            if (jsonName.equals(member.getValue().path("jsonName").asText(member.getKey()))) {
                return member;
            }
        }
        return null;
    }

    private static String timestampFormat(JsonNode service, JsonNode shape, String override) {
        return override != null ? override
                : shape.path("timestampFormat").asText(service.path("timestampFormat").asText("unixTimestamp"));
    }

    private static Instant instant(BigDecimal seconds) {
        try {
            BigDecimal integral = seconds.setScale(0, RoundingMode.DOWN);
            long nanos = seconds.subtract(integral).movePointRight(9).longValue();
            return Instant.ofEpochSecond(integral.longValueExact(), nanos);
        } catch (ArithmeticException | DateTimeException e) {
            throw serialization("CBOR timestamp is outside the supported range");
        }
    }

    private static AwsException serialization(String message) {
        return new AwsException("SerializationException", message, 400);
    }
}
