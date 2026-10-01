package io.github.hectorvent.floci.services.glue.schemaregistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apicurio.registry.content.ContentHandle;
import io.apicurio.registry.content.canon.AvroContentCanonicalizer;
import io.apicurio.registry.content.canon.ContentCanonicalizer;
import io.apicurio.registry.content.canon.JsonContentCanonicalizer;
import io.apicurio.registry.content.canon.ProtobufContentCanonicalizer;
import io.apicurio.registry.rules.compatibility.AvroCompatibilityChecker;
import io.apicurio.registry.rules.compatibility.CompatibilityChecker;
import io.apicurio.registry.rules.compatibility.CompatibilityDifference;
import io.apicurio.registry.rules.compatibility.CompatibilityExecutionResult;
import io.apicurio.registry.rules.compatibility.CompatibilityLevel;
import io.apicurio.registry.rules.compatibility.JsonSchemaCompatibilityChecker;
import io.apicurio.registry.rules.compatibility.ProtobufCompatibilityChecker;
import io.apicurio.registry.rules.validity.AvroContentValidator;
import io.apicurio.registry.rules.validity.ContentValidator;
import io.apicurio.registry.rules.validity.JsonSchemaContentValidator;
import io.apicurio.registry.rules.validity.ProtobufContentValidator;
import io.apicurio.registry.rules.validity.ValidityLevel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Glue → Apicurio adapter for schema compatibility, validation, and canonicalization.
 * Pure utility — no CDI. Stateless.
 */
public final class SchemaCompatibilityChecker {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> AVRO_RECORD_PROPERTIES = Set.of(
            "type", "name", "namespace", "doc", "aliases", "fields", "logicalType", "precision", "scale");
    private static final Set<String> AVRO_ENUM_PROPERTIES = Set.of(
            "type", "name", "namespace", "doc", "aliases", "symbols", "default",
            "logicalType", "precision", "scale");
    private static final Set<String> AVRO_FIXED_PROPERTIES = Set.of(
            "type", "name", "namespace", "doc", "aliases", "size", "logicalType", "precision", "scale");
    private static final Set<String> AVRO_ARRAY_PROPERTIES = Set.of(
            "type", "items", "default", "logicalType", "precision", "scale");
    private static final Set<String> AVRO_MAP_PROPERTIES = Set.of(
            "type", "values", "default", "logicalType", "precision", "scale");
    private static final Set<String> AVRO_PRIMITIVE_PROPERTIES = Set.of("type", "logicalType", "precision", "scale");
    private static final Set<String> AVRO_FIELD_PROPERTIES = Set.of(
            "name", "type", "doc", "default", "order", "aliases");

    public record Result(boolean compatible, String reason) {
        public static Result ok() {
            return new Result(true, null);
        }
    }

    private SchemaCompatibilityChecker() {}

    /**
     * Check whether {@code newDefinition} is compatible with {@code existingDefinitions}
     * under the given Glue compatibility {@code mode}.
     *
     * <p>{@code existingDefinitions} must be ordered by version ascending (oldest first,
     * latest last). For non-transitive modes (BACKWARD/FORWARD/FULL) only the latest
     * existing version is compared; for transitive modes (BACKWARD_ALL/FORWARD_ALL/FULL_ALL)
     * every prior version is compared.
     */
    public static Result check(String mode, List<String> existingDefinitions, String newDefinition, String dataFormat) {
        if (mode == null || existingDefinitions == null || existingDefinitions.isEmpty()) {
            return Result.ok();
        }
        if ("NONE".equals(mode) || "DISABLED".equals(mode)) {
            return Result.ok();
        }
        CompatibilityLevel level = toApicurioLevel(mode);
        CompatibilityChecker checker = checkerFor(dataFormat);

        List<ContentHandle> existing = existingDefinitions.stream()
                .map(ContentHandle::create)
                .collect(Collectors.toList());
        ContentHandle proposed = ContentHandle.create(newDefinition);

        CompatibilityExecutionResult result = checker.testCompatibility(level, existing, proposed, Map.of());
        if (result.isCompatible()) {
            return Result.ok();
        }
        return new Result(false, formatDifferences(result));
    }

    public static String canonicalize(String definition, String dataFormat) {
        ContentCanonicalizer canon = canonicalizerFor(dataFormat);
        ContentHandle handle = ContentHandle.create(definition);
        String canonical = canon.canonicalize(handle, Map.of()).content();
        if (!"AVRO".equals(dataFormat)) {
            return canonical;
        }
        try {
            JsonNode schema = JSON.readTree(canonical);
            stripAvroCustomAttributes(schema);
            return schema.toString();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to canonicalize AVRO schema", e);
        }
    }

    private static void stripAvroCustomAttributes(JsonNode schema) {
        if (schema instanceof ArrayNode union) {
            for (JsonNode branch : union) {
                stripAvroCustomAttributes(branch);
            }
        } else if (schema instanceof ObjectNode object) {
            Set<String> properties = switch (object.path("type").asText()) {
                case "record", "error" -> AVRO_RECORD_PROPERTIES;
                case "enum" -> AVRO_ENUM_PROPERTIES;
                case "fixed" -> AVRO_FIXED_PROPERTIES;
                case "array" -> AVRO_ARRAY_PROPERTIES;
                case "map" -> AVRO_MAP_PROPERTIES;
                default -> AVRO_PRIMITIVE_PROPERTIES;
            };
            object.retain(properties);
            stripAvroCustomAttributes(object.get("type"));
            stripAvroCustomAttributes(object.get("items"));
            stripAvroCustomAttributes(object.get("values"));
            if (object.get("fields") instanceof ArrayNode fields) {
                for (JsonNode field : fields) {
                    if (field instanceof ObjectNode fieldObject) {
                        fieldObject.retain(AVRO_FIELD_PROPERTIES);
                        stripAvroCustomAttributes(fieldObject.get("type"));
                    }
                }
            }
        }
    }

    /**
     * Validate that {@code definition} is parseable for the declared {@code dataFormat}.
     * @return null when valid; an error message when invalid.
     */
    public static String validateDefinition(String definition, String dataFormat) {
        ContentValidator validator = validatorFor(dataFormat);
        try {
            validator.validate(ValidityLevel.SYNTAX_ONLY, ContentHandle.create(definition), Map.of());
            return null;
        } catch (RuntimeException e) {
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
    }

    private static CompatibilityLevel toApicurioLevel(String glueMode) {
        return switch (glueMode) {
            case "NONE", "DISABLED" -> CompatibilityLevel.NONE;
            case "BACKWARD" -> CompatibilityLevel.BACKWARD;
            case "BACKWARD_ALL" -> CompatibilityLevel.BACKWARD_TRANSITIVE;
            case "FORWARD" -> CompatibilityLevel.FORWARD;
            case "FORWARD_ALL" -> CompatibilityLevel.FORWARD_TRANSITIVE;
            case "FULL" -> CompatibilityLevel.FULL;
            case "FULL_ALL" -> CompatibilityLevel.FULL_TRANSITIVE;
            default -> throw new IllegalArgumentException("Unknown compatibility mode: " + glueMode);
        };
    }

    private static CompatibilityChecker checkerFor(String dataFormat) {
        return switch (dataFormat) {
            case "AVRO" -> new AvroCompatibilityChecker();
            case "JSON" -> new JsonSchemaCompatibilityChecker();
            case "PROTOBUF" -> new ProtobufCompatibilityChecker();
            default -> throw new IllegalArgumentException("Unsupported DataFormat: " + dataFormat);
        };
    }

    private static ContentCanonicalizer canonicalizerFor(String dataFormat) {
        return switch (dataFormat) {
            case "AVRO" -> new AvroContentCanonicalizer();
            case "JSON" -> new JsonContentCanonicalizer();
            case "PROTOBUF" -> new ProtobufContentCanonicalizer();
            default -> throw new IllegalArgumentException("Unsupported DataFormat: " + dataFormat);
        };
    }

    private static ContentValidator validatorFor(String dataFormat) {
        return switch (dataFormat) {
            case "AVRO" -> new AvroContentValidator();
            case "JSON" -> new JsonSchemaContentValidator();
            case "PROTOBUF" -> new ProtobufContentValidator();
            default -> throw new IllegalArgumentException("Unsupported DataFormat: " + dataFormat);
        };
    }

    private static String formatDifferences(CompatibilityExecutionResult result) {
        if (result.getIncompatibleDifferences() == null || result.getIncompatibleDifferences().isEmpty()) {
            return "Schema is incompatible";
        }
        return result.getIncompatibleDifferences().stream()
                .map(d -> {
                    var rv = d.asRuleViolation();
                    String desc = rv != null ? rv.getDescription() : null;
                    String ctx = rv != null ? rv.getContext() : null;
                    if (desc == null) {
                        return d.toString();
                    }
                    return ctx == null ? desc : desc + " at " + ctx;
                })
                .collect(Collectors.joining("; "));
    }
}
