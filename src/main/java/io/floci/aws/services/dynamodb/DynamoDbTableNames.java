package io.floci.aws.services.dynamodb;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.SmithyValidationErrors;

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

    /** Whether the input is a non-empty name rather than a table ARN. */
    static boolean isShortName(String input) {
        return input != null && !input.isEmpty() && !AwsArnUtils.looksLikeArn(input);
    }

    /** Resolves the table of an item call, which reports only the first problem of a short name. */
    public static String resolveForItemCall(String input) {
        if (isShortName(input)) {
            String violation = nameViolation(input);
            if (violation != null) {
                throw SmithyValidationErrors.validationException(violation);
            }
        }
        return resolve(input);
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

        // Every call reports only the first problem of the name in a table ARN, measured in UTF-16 units.
        List<String> violations = violations(input, "Table name of ARN must ", tableName, tableName.length());
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations.getFirst());
        }
        return new ResolvedTableRef(tableName, region);
    }

    static boolean isTableArn(String value, String region) {
        try {
            return region.equals(parseArn(value).region())
                    && AwsRegions.partitionFor(region).equals(AwsArnUtils.parse(value).partition());
        } catch (AwsException notATableArn) {
            return false;
        }
    }

    private static void validateTableName(String tableName) {
        List<String> violations = nameViolations(tableName);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
    }

    /**
     * The first violation of a short table name as an item call reports it, or {@code null} for a
     * valid one. The length counts characters and hides a bad pattern.
     */
    static String nameViolation(String tableName) {
        List<String> violations = violations(tableName, "Member must ", tableName,
                tableName.codePointCount(0, tableName.length()));
        return violations.isEmpty() ? null : violations.getFirst();
    }

    /**
     * Every violation of a short table name as the table calls and the older item validator
     * report them, the length first. The length counts UTF-16 units, so an emoji counts twice.
     */
    static List<String> nameViolations(String tableName) {
        return violations(tableName, "Member must ", tableName, tableName.length());
    }

    /** The length problem, then the pattern problem, of a table name the error quotes as {@code value}. */
    private static List<String> violations(String value, String rule, String tableName, int length) {
        List<String> violations = new ArrayList<>();
        if (length < 3) {
            violations.add(violation(value, rule + "have length greater than or equal to 3"));
        } else if (255 < length) {
            violations.add(violation(value, rule + "have length less than or equal to 255"));
        }
        if (!TABLE_NAME_CHARS.matcher(tableName).matches()) {
            violations.add(violation(value, rule + "satisfy regular expression pattern: [a-zA-Z0-9_.-]+"));
        }
        return violations;
    }

    private static String violation(String value, String constraint) {
        return SmithyValidationErrors.Violations.QUOTED.constraint("tableName", value, constraint);
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
