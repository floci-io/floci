package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.regex.Pattern;

/**
 * Resolves DynamoDB {@code TableName} inputs that may be either a short table
 * name or a full table ARN.
 */
public final class DynamoDbTableNames {

    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_.-]{3,255}");
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("\\d{12}");

    private DynamoDbTableNames() {}

    public record ResolvedTableRef(String name, String region) {}

    public static String resolve(String input) {
        return resolveInternal(input).name();
    }

    /**
     * Validates a short table name. Rejects ARN-form input: callers (e.g. CreateTable)
     * must persist a canonical short name, not an ARN that would produce ARN-on-ARN
     * values when derived into {@code TableArn}.
     */
    public static String requireShortName(String input) {
        if (input == null || input.isBlank()) {
            throw invalid("TableName must not be blank");
        }
        if (input.startsWith("arn:")) {
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
        if (input == null || input.isBlank()) {
            throw invalid("TableName must not be blank");
        }
        if (input.startsWith("arn:")) {
            return parseArn(input);
        }
        validateTableName(input);
        return new ResolvedTableRef(input, null);
    }

    private static ResolvedTableRef parseArn(String input) {
        String[] parts = input.split(":", 6);
        if (parts.length != 6) {
            throw invalid("Invalid table ARN: " + input);
        }
        if (!"arn".equals(parts[0]) || !"aws".equals(parts[1]) || !"dynamodb".equals(parts[2])) {
            throw invalid("Invalid table ARN: " + input);
        }
        String region = parts[3];
        String account = parts[4];
        String resource = parts[5];
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
        if (!TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            throw invalid("Invalid TableName: " + tableName);
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValue", message, 400);
    }
}
