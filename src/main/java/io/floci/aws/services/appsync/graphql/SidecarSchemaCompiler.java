package io.floci.aws.services.appsync.graphql;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.appsync.GraphqlSidecarClient;
import io.floci.aws.services.appsync.GraphqlSidecarClient.SchemaIssue;
import io.floci.aws.services.appsync.GraphqlSidecarClient.SchemaValidationException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares a customer's SDL for the (AWS-agnostic) GraphQL sidecar and validates it against it
 * (issue #2917): what {@code AppSyncSchemaParser} used to do in-process. AppSync provides its
 * directive declarations ({@code @aws_auth} etc.) and the 17 custom scalar type declarations
 * implicitly: customers never write {@code scalar AWSDateTime} themselves. That injection is
 * pure AWS-specific knowledge (which directives exist, their exact argument shapes) with no
 * business living in the sidecar, so it happens here, before the SDL is ever sent anywhere.
 */
@ApplicationScoped
public class SidecarSchemaCompiler {

    /** Must match the scalar names {@code SCALAR_KIND_MAPPING} maps onto a sidecar coercion kind. */
    private static final List<String> SCALAR_NAMES = List.of(
            "AWSJSON", "AWSDateTime", "AWSDate", "AWSTime", "AWSTimestamp", "AWSEmail", "AWSURL",
            "AWSPhone", "AWSIPAddress", "AWSBoolean", "AWSLong", "AWSInteger", "AWSShort", "AWSFloat",
            "AWSBigDecimal", "AWSBigInt", "AWSByte");

    /**
     * The sidecar carries no AWS vocabulary: its scalars are generic named coercion kinds (see
     * {@code floci-io/floci-sidecars/graphql/API.md}), and a caller maps its own scalar names onto
     * them. This is that mapping for AppSync's 17 scalars, sent as the {@code scalars} field on
     * every {@code /v1/schema/validate}, {@code /v1/plan} and {@code /v1/execute} call. Without it
     * every AWS scalar would silently fall back to identity pass-through coercion in the sidecar.
     */
    private static final Map<String, String> SCALAR_KIND_MAPPING = Map.ofEntries(
            Map.entry("AWSJSON", "json-string"),
            Map.entry("AWSDateTime", "date-time"),
            Map.entry("AWSDate", "date"),
            Map.entry("AWSTime", "time"),
            Map.entry("AWSTimestamp", "epoch-seconds"),
            Map.entry("AWSEmail", "email"),
            Map.entry("AWSURL", "url"),
            Map.entry("AWSPhone", "phone"),
            Map.entry("AWSIPAddress", "ip-address"),
            Map.entry("AWSBoolean", "boolean"),
            Map.entry("AWSLong", "long"),
            Map.entry("AWSInteger", "integer"),
            Map.entry("AWSShort", "short"),
            Map.entry("AWSFloat", "float"),
            Map.entry("AWSBigDecimal", "big-decimal"),
            Map.entry("AWSBigInt", "big-integer"),
            Map.entry("AWSByte", "base64"));

    private static final Pattern DIRECTIVE_USE = Pattern.compile("@(\\w+)");
    private static final Pattern GRAPHQL_NAME = Pattern.compile("[_A-Za-z][_0-9A-Za-z]*");

    private final GraphqlSidecarClient sidecarClient;

    @Inject
    public SidecarSchemaCompiler(GraphqlSidecarClient sidecarClient) {
        this.sidecarClient = sidecarClient;
    }

    /** Validates the customer's raw {@code sdl} against the sidecar. Throws {@link AwsException} (400) if invalid. */
    public void validate(String sdl) {
        validateNoUnknownDirectives(sdl);
        try {
            sidecarClient.validateSchema(withDirectivesAndScalars(sdl), SCALAR_KIND_MAPPING);
        } catch (SchemaValidationException e) {
            throw toAwsException(e);
        }
    }

    /** The scalar-name-to-kind mapping every sidecar call must send alongside the prepared SDL. */
    public Map<String, String> scalarKindMapping() {
        return SCALAR_KIND_MAPPING;
    }

    /** The SDL to actually send the sidecar for plan/execute: same injection as at validation time. */
    public String withDirectivesAndScalars(String sdl) {
        List<String> declaredScalars = declaredScalars(sdl);
        StringBuilder sb = new StringBuilder();
        for (AppSyncDirective directive : AppSyncDirective.values()) {
            sb.append(directive.sdl()).append('\n');
        }
        sb.append('\n');
        for (String scalarName : SCALAR_NAMES) {
            if (!declaredScalars.contains(scalarName)) {
                sb.append("scalar ").append(scalarName).append('\n');
            }
        }
        sb.append('\n').append(sdl);
        return sb.toString();
    }

    private void validateNoUnknownDirectives(String sdl) {
        String clean = stripIgnoredTokens(sdl);
        Matcher matcher = DIRECTIVE_USE.matcher(clean);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!AppSyncDirective.isKnown(name)) {
                throw new AwsException("BadRequestException", "Unknown directive: @" + name, 400,
                        buildExtendedData(List.of(
                                toCodeError("VALIDATION_ERROR", "Unknown directive: @" + name, 0, 0))));
            }
        }
    }

    private List<String> declaredScalars(String sdl) {
        String clean = stripIgnoredTokens(sdl);
        List<String> declaredScalars = new ArrayList<>();
        int braces = 0;
        int parentheses = 0;
        int brackets = 0;
        String previousTopLevelToken = "";
        int index = 0;
        while (index < clean.length()) {
            char current = clean.charAt(index);
            if (current == '{') {
                braces++;
                index++;
            } else if (current == '}') {
                braces--;
                index++;
            } else if (current == '(') {
                parentheses++;
                index++;
            } else if (current == ')') {
                parentheses--;
                index++;
            } else if (current == '[') {
                brackets++;
                index++;
            } else if (current == ']') {
                brackets--;
                index++;
            } else if (isGraphqlNameStart(current)) {
                int tokenStart = index++;
                while (index < clean.length() && isGraphqlNamePart(clean.charAt(index))) {
                    index++;
                }
                String token = clean.substring(tokenStart, index);
                if (braces == 0 && parentheses == 0 && brackets == 0) {
                    if (token.equals("scalar") && !previousTopLevelToken.equals("extend")) {
                        int nameStart = skipIgnored(clean, index);
                        Matcher name = GRAPHQL_NAME.matcher(clean);
                        name.region(nameStart, clean.length());
                        if (name.lookingAt()) {
                            declaredScalars.add(name.group());
                        }
                    }
                    previousTopLevelToken = token;
                }
            } else {
                if (braces == 0 && parentheses == 0 && brackets == 0
                        && !Character.isWhitespace(current) && current != ',') {
                    previousTopLevelToken = String.valueOf(current);
                }
                index++;
            }
        }
        return declaredScalars;
    }

    private int skipIgnored(String sdl, int index) {
        while (index < sdl.length()
                && (Character.isWhitespace(sdl.charAt(index)) || sdl.charAt(index) == ',')) {
            index++;
        }
        return index;
    }

    private boolean isGraphqlNameStart(char value) {
        return value == '_' || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private boolean isGraphqlNamePart(char value) {
        return isGraphqlNameStart(value) || value >= '0' && value <= '9';
    }

    /** Replaces GraphQL comments and string tokens while preserving whitespace and line breaks. */
    private String stripIgnoredTokens(String sdl) {
        StringBuilder clean = new StringBuilder(sdl.length());
        int index = 0;
        while (index < sdl.length()) {
            char current = sdl.charAt(index);
            if (current == '#') {
                while (index < sdl.length() && sdl.charAt(index) != '\n' && sdl.charAt(index) != '\r') {
                    clean.append(' ');
                    index++;
                }
            } else if (sdl.startsWith("\"\"\"", index)) {
                clean.append("   ");
                index += 3;
                while (index < sdl.length() && !sdl.startsWith("\"\"\"", index)) {
                    char blockChar = sdl.charAt(index);
                    if (blockChar == '\\' && sdl.startsWith("\\\"\"\"", index)) {
                        clean.append("    ");
                        index += 4;
                    } else {
                        clean.append(blockChar == '\n' || blockChar == '\r' ? blockChar : ' ');
                        index++;
                    }
                }
                if (index < sdl.length()) {
                    clean.append("   ");
                    index += 3;
                }
            } else if (current == '"') {
                clean.append(' ');
                index++;
                while (index < sdl.length() && sdl.charAt(index) != '"') {
                    if (sdl.charAt(index) == '\\' && index + 1 < sdl.length()) {
                        clean.append("  ");
                        index += 2;
                    } else {
                        char stringChar = sdl.charAt(index);
                        clean.append(stringChar == '\n' || stringChar == '\r' ? stringChar : ' ');
                        index++;
                    }
                }
                if (index < sdl.length()) {
                    clean.append(' ');
                    index++;
                }
            } else {
                clean.append(current);
                index++;
            }
        }
        return clean.toString();
    }

    private AwsException toAwsException(SchemaValidationException e) {
        List<Map<String, Object>> codeErrors = new ArrayList<>();
        for (SchemaIssue issue : e.issues()) {
            codeErrors.add(toCodeError(issue.category(), issue.message(), issue.line(), issue.column()));
        }
        if (codeErrors.isEmpty()) {
            codeErrors.add(toCodeError("VALIDATION_ERROR", e.getMessage(), 0, 0));
        }
        return new AwsException("BadRequestException", "Invalid schema: " + e.getMessage(), 400,
                buildExtendedData(codeErrors));
    }

    private Map<String, Object> toCodeError(String errorType, String value, int line, int column) {
        Map<String, Object> codeError = new LinkedHashMap<>();
        codeError.put("errorType", errorType);
        codeError.put("value", value);
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("line", line);
        location.put("column", column);
        location.put("span", -1);
        codeError.put("location", location);
        return codeError;
    }

    private Map<String, Object> buildExtendedData(List<Map<String, Object>> codeErrors) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("codeErrors", codeErrors);
        Map<String, Object> extendedData = new LinkedHashMap<>();
        extendedData.put("reason", "CODE_ERROR");
        extendedData.put("detail", detail);
        return extendedData;
    }
}
