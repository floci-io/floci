package io.github.hectorvent.floci.services.glue.schemaregistry;

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
import java.util.stream.Collectors;

/**
 * Glue → Apicurio adapter for schema compatibility, validation, and canonicalization.
 * Pure utility — no CDI. Stateless.
 */
public final class SchemaCompatibilityChecker {

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
        return canon.canonicalize(handle, Map.of()).content();
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
