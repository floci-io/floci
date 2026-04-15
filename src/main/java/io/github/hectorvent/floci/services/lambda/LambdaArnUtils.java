package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.regex.Pattern;

/**
 * Parses the many forms AWS Lambda accepts for a {@code FunctionName} path
 * parameter: bare name, partial ARN ({@code ACCT:function:NAME}), or full ARN
 * ({@code arn:aws:lambda:REGION:ACCT:function:NAME}), each optionally suffixed
 * with {@code :qualifier} (version or alias).
 */
public final class LambdaArnUtils {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9-_]+");
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
        String[] parts = input.split(":", -1);
        if (parts.length < 7 || parts.length > 8) {
            throw invalid("Invalid ARN: " + input);
        }
        if (!"arn".equals(parts[0]) || !"aws".equals(parts[1]) || !"lambda".equals(parts[2])) {
            throw invalid("Invalid ARN: " + input);
        }
        String region = parts[3];
        String account = parts[4];
        if (region.isBlank()) {
            throw invalid("ARN missing region: " + input);
        }
        if (!ACCOUNT_PATTERN.matcher(account).matches()) {
            throw invalid("ARN has invalid account id: " + input);
        }
        if (!"function".equals(parts[5])) {
            throw invalid("ARN resource type must be 'function': " + input);
        }
        String name = parts[6];
        validateName(name);
        String qualifier = parts.length == 8 ? parts[7] : null;
        if (qualifier != null) {
            validateQualifier(qualifier);
        }
        return new ResolvedFunctionRef(name, qualifier, region);
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
}
