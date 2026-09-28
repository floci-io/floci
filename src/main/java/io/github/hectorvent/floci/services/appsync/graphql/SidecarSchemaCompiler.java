package io.github.hectorvent.floci.services.appsync.graphql;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.SchemaIssue;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.SchemaValidationException;
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
        StringBuilder sb = new StringBuilder();
        for (AppSyncDirective directive : AppSyncDirective.values()) {
            sb.append(directive.sdl()).append('\n');
        }
        sb.append('\n');
        for (String scalarName : SCALAR_NAMES) {
            sb.append("scalar ").append(scalarName).append('\n');
        }
        sb.append('\n').append(sdl);
        return sb.toString();
    }

    private void validateNoUnknownDirectives(String sdl) {
        String clean = sdl
                .replaceAll("\"\"\"[\\s\\S]*?\"\"\"", "")
                .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "")
                .replaceAll("#[^\n]*", "");
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
