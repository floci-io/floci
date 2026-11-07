package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * A template body read as CloudFormation reads it (measured, eu-west-1, 2026-10-21): as YAML, of
 * which JSON is a subset, so a body that is not strict JSON, such as {@code {a: 1}} or a trailing
 * comma, still reads as YAML's flow style. A body that reads as neither is "JSON not well-formed"
 * when it starts with {@code {} and ends with {@code }}, at the JSON parser's position, and "YAML
 * not well-formed" otherwise, at the YAML parser's; both positions are 1-based.
 */
final class CloudFormationTemplateParser {

    private final ObjectMapper objectMapper;

    CloudFormationTemplateParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** A body that reads as neither JSON nor YAML, with CloudFormation's words for it. */
    static final class TemplateFormatException extends Exception {

        TemplateFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    JsonNode parse(String templateBody) throws TemplateFormatException {
        String body = templateBody != null ? templateBody : "{}";
        String trimmed = body.trim();
        JsonProcessingException jsonError = null;
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            } catch (JsonProcessingException e) {
                jsonError = e;
            }
        }
        try {
            // The CF-aware parser turns !Sub, !Ref, !GetAtt and the other short forms into their maps.
            return new CloudFormationYamlParser(objectMapper).parse(body);
        } catch (YAMLException yamlError) {
            if (jsonError != null && trimmed.startsWith("{") && trimmed.endsWith("}")) {
                JsonLocation at = jsonError.getLocation();
                throw new TemplateFormatException("JSON not well-formed. (line " + at.getLineNr()
                        + ", column " + at.getColumnNr() + ")", jsonError);
            }
            throw new TemplateFormatException("YAML not well-formed." + position(yamlError), yamlError);
        }
    }

    /** Where the YAML parser stopped, 1-based as CloudFormation counts; nothing for an error with no mark. */
    private static String position(YAMLException error) {
        if (!(error instanceof MarkedYAMLException marked)) {
            return "";
        }
        Mark mark = marked.getProblemMark() != null ? marked.getProblemMark() : marked.getContextMark();
        return mark == null ? "" : " (line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1) + ")";
    }
}
