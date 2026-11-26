package io.floci.aws.services.ssm.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.DeadlineCharSequence;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ssm.SsmService;
import io.floci.aws.services.ssm.model.SsmDocument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class SessionDocumentResolver {
    public static final String DEFAULT_DOCUMENT_NAME = "SSM-SessionManagerRunShell";

    private static final String IMPLICIT_SHELL = """
            {"schemaVersion":"1.0","sessionType":"Standard_Stream"}
            """;
    private static final ObjectMapper DOCUMENT_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final int MAX_DOCUMENT_BYTES = 65_536;
    private static final long PATTERN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final SsmService ssmService;
    private final RegionResolver regionResolver;

    @Inject
    SessionDocumentResolver(SsmService ssmService, RegionResolver regionResolver) {
        this.ssmService = ssmService;
        this.regionResolver = regionResolver;
    }

    public ResolvedDocument resolve(String region, String documentName, Map<String, List<String>> parameters) {
        String accountId = regionResolver.getAccountId();
        String name = documentName == null ? DEFAULT_DOCUMENT_NAME : documentName(documentName, region, accountId);
        SsmDocument document = ssmService.findDocument(name, region).orElse(null);
        String source;
        if (document == null && documentName == null) {
            source = IMPLICIT_SHELL;
        } else if (document == null) {
            throw missingDocument(documentName);
        } else if (!"Session".equals(document.getDocumentType())) {
            throw invalidDocument("Document type: '" + document.getDocumentType()
                    + "' is not supported. Only type: 'Session' is supported for session manager.");
        } else {
            source = document.getContent();
        }
        JsonNode content = parseDocument(name, source);
        Map<String, Object> resolved = resolveParameters(content.path("parameters"), parameters);
        validateInputs(content.path("inputs"));
        return new ResolvedDocument(name, SsmService.documentArn(region, accountId, name),
                content, resolved);
    }

    private static String documentName(String nameOrArn, String region, String accountId) {
        if (!AwsArnUtils.looksLikeArn(nameOrArn)) {
            return nameOrArn;
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(nameOrArn);
        } catch (IllegalArgumentException e) {
            throw missingDocument(nameOrArn);
        }
        String prefix = "document/";
        if (!arn.resource().startsWith(prefix)) {
            throw missingDocument(nameOrArn);
        }
        String name = arn.resource().substring(prefix.length());
        if (!nameOrArn.equals(SsmService.documentArn(region, accountId, name))) {
            throw missingDocument(nameOrArn);
        }
        return name;
    }

    private static AwsException missingDocument(String name) {
        return invalidDocument("Document with name " + name + " does not exist.");
    }

    private static JsonNode parseDocument(String name, String source) {
        if (source != null && (source.length() > MAX_DOCUMENT_BYTES
                || source.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES)) {
            throw invalidDocument("Document " + name + " exceeds the " + MAX_DOCUMENT_BYTES + " byte size limit.");
        }
        try {
            JsonNode content = source == null ? null : DOCUMENT_MAPPER.readTree(source);
            if (content == null || !content.isObject()
                    || !"1.0".equals(content.path("schemaVersion").asText())
                    || (content.hasNonNull("parameters") && !content.path("parameters").isObject())) {
                throw invalidDocument("Document " + name + " does not contain a valid Session document.");
            }
            String sessionType = content.path("sessionType").asText();
            if (!"Standard_Stream".equals(sessionType) && !"Port".equals(sessionType)) {
                throw invalidDocument("Session type '" + sessionType + "' is not supported.");
            }
            return content;
        } catch (JsonProcessingException e) {
            throw invalidDocument("Document " + name + " does not contain a valid Session document.");
        }
    }

    private static Map<String, Object> resolveParameters(JsonNode definitions, Map<String, List<String>> supplied) {
        for (String name : supplied.keySet()) {
            if (!definitions.has(name)) {
                throw invalidParameters("Parameter \"" + name + "\" is not defined in the document.");
            }
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : definitions.properties()) {
            String name = entry.getKey();
            JsonNode definition = entry.getValue();
            boolean scalar = switch (definition.path("type").asText()) {
                case "String" -> true;
                case "StringList" -> false;
                default -> throw invalidDocument("Parameter \"" + name + "\" has an unsupported type.");
            };
            List<String> values = supplied.containsKey(name)
                    ? supplied.get(name) : defaultValues(name, definition.path("default"), scalar);
            if (scalar && values.size() != 1) {
                throw invalidParameters("Parameter \"" + name + "\" takes exactly one value, got " + values.size() + ".");
            }
            if (!scalar) {
                validateLength(name, definition, values.size(), "minItems", "maxItems");
            }
            validateValues(name, definition, values);
            validatePattern(name, definition.path("allowedPattern").asText(), values);
            resolved.put(name, scalar ? values.getFirst() : List.copyOf(values));
        }
        return Map.copyOf(resolved);
    }

    private static List<String> defaultValues(String name, JsonNode defaultValue, boolean scalar) {
        if (defaultValue.isMissingNode() || defaultValue.isNull()) {
            throw invalidParameters("Parameter \"" + name + "\" requires a value.");
        }
        if (scalar && defaultValue.isTextual()) {
            return List.of(defaultValue.textValue());
        }
        if (!scalar && defaultValue.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode value : defaultValue) {
                if (!value.isTextual()) {
                    throw invalidDocument("Parameter \"" + name + "\" has an invalid default value.");
                }
                values.add(value.textValue());
            }
            return values;
        }
        throw invalidDocument("Parameter \"" + name + "\" has an invalid default value.");
    }

    private static void validateValues(String name, JsonNode definition, List<String> values) {
        JsonNode allowedValues = definition.path("allowedValues");
        if (!allowedValues.isMissingNode() && !allowedValues.isArray()) {
            throw invalidDocument("Parameter \"" + name + "\" has invalid allowedValues.");
        }
        for (String value : values) {
            if (allowedValues.isArray()
                    && allowedValues.valueStream().noneMatch(allowed -> value.equals(allowed.textValue()))) {
                throw invalidParameters("Parameter \"" + name + "\" has value \"" + value
                        + "\" outside its allowedValues.");
            }
            validateLength(name, definition, value.length(), "minChars", "maxChars");
        }
    }

    private static void validateLength(String name, JsonNode definition, int length, String min, String max) {
        if (length < definition.path(min).asInt(0)) {
            throw invalidParameters("Parameter \"" + name + "\" violates " + min + ".");
        }
        if (length > definition.path(max).asInt(Integer.MAX_VALUE)) {
            throw invalidParameters("Parameter \"" + name + "\" violates " + max + ".");
        }
    }

    private static void validatePattern(String name, String allowedPattern, List<String> values) {
        if (allowedPattern.isEmpty()) {
            return;
        }
        try {
            Pattern pattern = Pattern.compile(allowedPattern);
            long deadline = System.nanoTime() + PATTERN_TIMEOUT_NANOS;
            for (String value : values) {
                if (!pattern.matcher(new DeadlineCharSequence(value, deadline, "AllowedPattern timed out")).matches()) {
                    throw invalidParameters("Parameter \"" + name + "\" has value \"" + value
                            + "\" not matching \"" + allowedPattern + "\".");
                }
            }
        } catch (PatternSyntaxException | IllegalStateException e) {
            throw invalidDocument("Parameter \"" + name + "\" has an invalid or overly complex allowedPattern.");
        }
    }

    private static void validateInputs(JsonNode inputs) {
        if (!inputs.isMissingNode() && !inputs.isObject()) {
            throw invalidDocument("Session document inputs must be an object.");
        }
        if (!inputs.path("kmsKeyId").asText().isBlank()
                || !inputs.path("s3BucketName").asText().isBlank()
                || !inputs.path("cloudWatchLogGroupName").asText().isBlank()) {
            throw invalidDocument("Session Manager KMS encryption and session logging are not supported.");
        }
    }

    private static AwsException invalidDocument(String message) {
        return new AwsException("InvalidDocument", message, 400);
    }

    private static AwsException invalidParameters(String message) {
        return new AwsException("InvalidParameters", message, 400);
    }

    public record ResolvedDocument(String name, String arn, JsonNode content, Map<String, Object> parameters) { }
}
