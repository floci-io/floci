package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves DynamoDB {@code TableName} inputs that may be either a short table
 * name or a full table ARN.
 */
public final class DynamoDbTableNames {

    static final Pattern TABLE_NAME_CHARS = Pattern.compile("[a-zA-Z0-9_.\\-]+");
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("\\d{12}");

    private DynamoDbTableNames() {}

    public record ResolvedTableRef(String name, String region) {}

    public static String resolve(String input) {
        return resolveInternal(input).name();
    }

    /**
     * Validates a short table name for use with CreateTable.
     * CreateTable requires min length 3 and missing TableName is a distinct error.
     */
    public static String requireShortName(String input) {
        if (input == null) {
            throw new AwsException("ValidationException",
                    "The parameter 'TableName' is required but was not present in the request", 400);
        }
        if (input.isEmpty()) {
            throw SmithyValidationErrors.validationException(
                    SmithyValidationErrors.lengthOutOfRange("tableName", input, 3, 255));
        }
        if (AwsArnUtils.looksLikeArn(input)) {
            throw invalid("TableName must be a short name, not an ARN: " + input);
        }
        validateTableName(input);
        return input;
    }

    public static ResolvedTableRef resolveWithRegion(String input, String requestRegion) {
        ResolvedTableRef ref = resolveInternal(input);
        if (ref.region() != null && !ref.region().equals(requestRegion)) {
            throw invalid("Region '" + ref.region() + "' in ARN does not match request region '" + requestRegion + "'");
        }
        return ref;
    }

    private static ResolvedTableRef resolveInternal(String input) {
        if (input == null) {
            throw SmithyValidationErrors.validationException(
                    SmithyValidationErrors.notNull("tableName"));
        }
        if (input.isEmpty()) {
            throw SmithyValidationErrors.validationException(
                    SmithyValidationErrors.lengthOutOfRange("tableName", input, 1, 255));
        }
        if (AwsArnUtils.looksLikeArn(input)) {
            return parseArn(input);
        }
        validateTableName(input);
        return new ResolvedTableRef(input, null);
    }

    private static ResolvedTableRef parseArn(String input) {
        AwsArnUtils.Arn base;
        try {
            base = AwsArnUtils.parse(input);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid table ARN: " + input);
        }
        if (!"dynamodb".equals(base.service())) {
            throw invalid("Invalid table ARN: " + input);
        }
        String region = base.region();
        String account = base.accountId();
        String resource = base.resource();
        if (region.isBlank()) {
            throw invalid("Table ARN missing region: " + input);
        }
        if (!ACCOUNT_PATTERN.matcher(account).matches()) {
            throw invalid("Table ARN has invalid account id: " + input);
        }
        if (!resource.startsWith("table/")) {
            throw invalid("Table ARN resource must start with 'table/': " + input);
        }

        String tableResource = resource.substring("table/".length());
        int slash = tableResource.indexOf('/');
        String tableName = slash >= 0 ? tableResource.substring(0, slash) : tableResource;
        if (tableName.isEmpty()) {
            throw invalid("Table ARN is missing table name: " + input);
        }
        if (slash >= 0) {
            String suffix = tableResource.substring(slash + 1);
            if (suffix.startsWith("index/") || suffix.startsWith("stream/")) {
                throw invalid("TableName does not accept index or stream ARNs: " + input);
            }
            throw invalid("Invalid table ARN: " + input);
        }

        validateTableName(tableName);
        return new ResolvedTableRef(tableName, region);
    }

    private static void validateTableName(String tableName) {
        String violation = nameViolation(tableName);
        if (violation != null) {
            throw SmithyValidationErrors.validationException(violation);
        }
    }

    /** The violation for a short table name, or {@code null} for a valid one. A bad length hides a bad pattern. */
    static String nameViolation(String tableName) {
        List<String> violations = nameViolations(tableName);
        return violations.isEmpty() ? null : violations.getFirst();
    }

    /** Every violation of a short table name, the length first. */
    static List<String> nameViolations(String tableName) {
        List<String> violations = new ArrayList<>();
        String lengthViolation = SmithyValidationErrors.lengthOutOfRange("tableName", tableName, 3, 255);
        if (lengthViolation != null) {
            violations.add(lengthViolation);
        }
        if (!TABLE_NAME_CHARS.matcher(tableName).matches()) {
            violations.add("Value '" + tableName
                    + "' at 'tableName' failed to satisfy constraint: Member must satisfy regular expression pattern: [a-zA-Z0-9_.-]+");
        }
        return violations;
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
