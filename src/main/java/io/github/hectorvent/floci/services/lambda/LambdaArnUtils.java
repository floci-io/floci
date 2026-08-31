package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.regex.Pattern;

/**
 * Parses the many forms AWS Lambda accepts for a {@code FunctionName} path
 * parameter: bare name, partial ARN ({@code ACCT:function:NAME}), or full ARN
 * ({@code arn:aws:lambda:REGION:ACCT:function:NAME}), each optionally suffixed
 * with {@code :qualifier} (version or alias).
 */
public final class LambdaArnUtils {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9-_.]+");
    private static final int MAX_NAME_LENGTH = 256;
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("\\d{12}");
    private static final Pattern QUALIFIER_PATTERN = Pattern.compile("\\$LATEST|[a-zA-Z0-9-_]+");

    private LambdaArnUtils() {}

    /**
     * Resolved components of a Lambda function reference.
     *
     * @param name      short function name (never null/blank)
     * @param qualifier version or alias, or null if absent
     * @param region    region extracted from a full ARN, or null for bare
     *                  name / partial ARN inputs
     */
    public record ResolvedFunctionRef(String name, String qualifier, String region) {}

    /**
     * Parses a {@code FunctionName} path parameter. Throws
     * {@link AwsException} ({@code InvalidParameterValueException}, HTTP 400)
     * on any malformed input.
     */
    public static ResolvedFunctionRef resolve(String input) {
        if (input == null || input.isBlank()) {
            throw invalid("FunctionName must not be blank");
        }

        if (input.startsWith("arn:")) {
            return parseFullArn(input);
        }
        if (input.contains(":function:")) {
            return parsePartialArn(input);
        }
        return parseNameWithOptionalQualifier(input);
    }

    /**
     * Resolves an input and reconciles any embedded qualifier with a
     * {@code ?Qualifier=} query-string value. If both are supplied and differ,
     * throws 400. Returns the effective qualifier (may be null).
     */
    public static ResolvedFunctionRef resolveWithQualifier(String input, String queryQualifier) {
        ResolvedFunctionRef ref = resolve(input);
        String embedded = ref.qualifier();
        String normalizedQuery = (queryQualifier == null || queryQualifier.isBlank()) ? null : queryQualifier;

        if (embedded != null && normalizedQuery != null && !embedded.equals(normalizedQuery)) {
            throw invalid("The derived qualifier from the function name does not match the specified qualifier.");
        }
        String effective = embedded != null ? embedded : normalizedQuery;
        if (effective != null && !QUALIFIER_PATTERN.matcher(effective).matches()) {
            throw invalid("Invalid qualifier: " + effective);
        }
        return new ResolvedFunctionRef(ref.name(), effective, ref.region());
    }

    private static ResolvedFunctionRef parseFullArn(String input) {
        // arn:aws:lambda:REGION:ACCT:function:NAME[:QUALIFIER]
        AwsArnUtils.Arn base;
        try {
            base = AwsArnUtils.parse(input);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid ARN: " + input);
        }
        if (!"lambda".equals(base.service())) {
            throw invalid("Invalid ARN: " + input);
        }
        if (base.region().isBlank()) {
            throw invalid("ARN missing region: " + input);
        }
        if (!ACCOUNT_PATTERN.matcher(base.accountId()).matches()) {
            throw invalid("ARN has invalid account id: " + input);
        }
        // resource is "function:NAME" or "function:NAME:QUALIFIER"
        String resource = base.resource();
        String[] resParts = resource.split(":", -1);
        if (resParts.length < 2 || resParts.length > 3) {
            throw invalid("Invalid ARN: " + input);
        }
        if (!"function".equals(resParts[0])) {
            throw invalid("ARN resource type must be 'function': " + input);
        }
        String name = resParts[1];
        validateName(name);
        String qualifier = resParts.length == 3 ? resParts[2] : null;
        if (qualifier != null) {
            validateQualifier(qualifier);
        }
        return new ResolvedFunctionRef(name, qualifier, base.region());
    }

    private static ResolvedFunctionRef parsePartialArn(String input) {
        // ACCT:function:NAME[:QUALIFIER]
        String[] parts = input.split(":", -1);
        if (parts.length < 3 || parts.length > 4) {
            throw invalid("Invalid partial ARN: " + input);
        }
        if (!"function".equals(parts[1])) {
            throw invalid("Partial ARN resource type must be 'function': " + input);
        }
        String account = parts[0];
        if (!ACCOUNT_PATTERN.matcher(account).matches()) {
            throw invalid("Partial ARN has invalid account id: " + input);
        }
        String name = parts[2];
        validateName(name);
        String qualifier = parts.length == 4 ? parts[3] : null;
        if (qualifier != null) {
            validateQualifier(qualifier);
        }
        return new ResolvedFunctionRef(name, qualifier, null);
    }

    private static ResolvedFunctionRef parseNameWithOptionalQualifier(String input) {
        // NAME[:QUALIFIER]
        String[] parts = input.split(":", -1);
        if (parts.length > 2) {
            throw invalid("Invalid FunctionName: " + input);
        }
        String name = parts[0];
        validateName(name);
        String qualifier = parts.length == 2 ? parts[1] : null;
        if (qualifier != null) {
            validateQualifier(qualifier);
        }
        return new ResolvedFunctionRef(name, qualifier, null);
    }

    private static void validateName(String name) {
        if (name == null || name.isEmpty()) {
            throw invalid("FunctionName segment is empty");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw invalid("FunctionName exceeds maximum length of " + MAX_NAME_LENGTH + ": " + name);
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw invalid("FunctionName contains invalid characters: " + name);
        }
    }

    private static void validateQualifier(String qualifier) {
        if (qualifier.isEmpty()) {
            throw invalid("Qualifier segment is empty");
        }
        if (!QUALIFIER_PATTERN.matcher(qualifier).matches()) {
            throw invalid("Invalid qualifier: " + qualifier);
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    /**
     * Extracts the Lambda function reference from an API Gateway integration URI.
     * Handles formats like:
     * <ul>
     *   <li>{@code arn:aws:lambda:us-east-1:000000000000:function:myFn/invocations}</li>
     *   <li>{@code arn:aws:lambda:us-east-1:000000000000:function:myFn}</li>
     *   <li>{@code arn:aws:lambda:us-east-1:000000000000:function:myFn:PROD/invocations} (alias/version)</li>
     *   <li>{@code myFn} (bare function name)</li>
     * </ul>
     *
     * <p>Any trailing {@code :qualifier} (alias or version) is preserved in the returned
     * value (e.g. {@code myFn:PROD}) so downstream {@code invoke} calls, which resolve the
     * reference via {@link #resolve(String)}, target the configured alias/version rather
     * than reducing to the bare function name and invoking {@code $LATEST}.
     *
     * @param uri the integration URI (may be null)
     * @return the extracted function reference (name, optionally with {@code :qualifier}),
     *         or null if the URI is null or unparseable
     */
    public static String extractFunctionNameFromUri(String uri) {
        if (uri == null) {
            return null;
        }
        try {
            // Parse as a full ARN — resource is "function:myFn" or "function:myFn/invocations"
            String resource = AwsArnUtils.parse(uri).resource();
            String[] parts = resource.split("/");
            // API Gateway v1 style: resource is "path/2015-03-31/functions/{lambdaArn}/invocations"
            // In this case recurse on the embedded Lambda ARN
            if (parts.length >= 4 && "path".equals(parts[0]) && "functions".equals(parts[2])) {
                // parts[3] onwards is the embedded Lambda ARN
                String embeddedArn = String.join("/", java.util.Arrays.copyOfRange(parts, 3, parts.length));
                return extractFunctionNameFromUri(embeddedArn);
            }
            // Standard Lambda ARN: parts[0] is "function:myFn" or "function:myFn:qualifier".
            // Strip only the leading "function:" resource-type prefix, preserving any
            // trailing :qualifier (alias/version) so the invoke target is not lost.
            String functionPart = parts[0];
            String functionPrefix = "function:";
            if (functionPart.startsWith(functionPrefix)) {
                return functionPart.substring(functionPrefix.length());
            }
            // Fallback for malformed/non-standard resources: every real Lambda ARN resource
            // starts with "function:" and is handled above, so this only fires on unexpected input.
            int colon = functionPart.lastIndexOf(':');
            return colon >= 0 ? functionPart.substring(colon + 1) : functionPart;
        } catch (IllegalArgumentException e) {
            // Not a valid ARN — treat the entire URI as the function name
            return uri;
        }
    }
}
