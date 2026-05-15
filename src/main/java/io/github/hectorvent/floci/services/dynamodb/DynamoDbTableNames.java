package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.regex.Pattern;

/**
 * Resolves DynamoDB {@code TableName} inputs that may be either a short table
 * name or a full table ARN.
 */
public final class DynamoDbTableNames {

    private static final Pattern TABLE_NAME_CHARS = Pattern.compile("[a-zA-Z0-9_.\\-]+");
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
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + input
                    + "' at 'tableName' failed to satisfy constraint: Member must have length greater than or equal to 3", 400);
        }
        if (input.startsWith("arn:")) {
            throw invalid("TableName must be a short name, not an ARN: " + input);
        }
        validateTableNameForCreate(input);
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
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'tableName' failed to satisfy constraint: Member must not be null", 400);
        }
        if (input.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '' at 'tableName' failed to satisfy constraint: Member must have length greater than or equal to 1", 400);
        }
        if (input.startsWith("arn:")) {
            return parseArn(input);
        }
        validateTableNameForOps(input);
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

        validateTableNameForOps(tableName);
        return new ResolvedTableRef(tableName, region);
    }

    // Validation for CreateTable: min length 3, max 255, pattern
    private static void validateTableNameForCreate(String tableName) {
        if (tableName.length() < 3) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + tableName
                    + "' at 'tableName' failed to satisfy constraint: Member must have length greater than or equal to 3", 400);
        }
        if (tableName.length() > 255) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + tableName
                    + "' at 'tableName' failed to satisfy constraint: Member must have length less than or equal to 255", 400);
        }
        if (!TABLE_NAME_CHARS.matcher(tableName).matches()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + tableName
                    + "' at 'tableName' failed to satisfy constraint: Member must satisfy regular expression pattern: [a-zA-Z0-9_.-]+", 400);
        }
    }

    // Validation for other operations (PutItem, GetItem, etc.): min length 3, max 255, pattern
    private static void validateTableNameForOps(String tableName) {
        if (tableName.length() < 3) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + tableName
                    + "' at 'tableName' failed to satisfy constraint: Member must have length greater than or equal to 3", 400);
        }
        if (tableName.length() > 255) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + tableName
                    + "' at 'tableName' failed to satisfy constraint: Member must have length less than or equal to 255", 400);
        }
        if (!TABLE_NAME_CHARS.matcher(tableName).matches()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + tableName
                    + "' at 'tableName' failed to satisfy constraint: Member must satisfy regular expression pattern: [a-zA-Z0-9_.-]+", 400);
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
