package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the members of a Redshift destination against the Firehose API model: required
 * members on create, length and pattern limits, the SNAPPY/ZIP restriction on the intermediate
 * S3 location, and the one-way S3 backup switch.
 */
final class RedshiftDestinationValidator {

    private static final String JDBC_URL_REGEX =
            "jdbc:(redshift|postgresql)://((?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.)+(redshift(-serverless)?)\\.([a-zA-Z0-9\\.\\-]+):\\d{1,5}/[a-zA-Z0-9_$-]+";
    private static final Pattern JDBC_URL = Pattern.compile(JDBC_URL_REGEX);
    private static final String ROLE_ARN_REGEX = "arn:.*:iam::\\d{12}:role/[a-zA-Z_0-9+=,.@\\-_/]+";
    private static final Pattern ROLE_ARN = Pattern.compile(ROLE_ARN_REGEX);
    private static final String SECRET_ARN_REGEX =
            "arn:.*:secretsmanager:[a-zA-Z0-9\\-]+:\\d{12}:secret:[a-zA-Z0-9\\-/_+=.@!]+";
    private static final Pattern SECRET_ARN = Pattern.compile(SECRET_ARN_REGEX);
    private static final Pattern UNREADABLE_KEY_CHARS = Pattern.compile("[\\s'\"]");
    private static final Set<String> BACKUP_MODES = Set.of("Disabled", "Enabled");
    private static final Set<String> UNSUPPORTED_COMPRESSION = Set.of("zip", "snappy");

    private RedshiftDestinationValidator() {}

    static void validateConfiguration(RedshiftDestination config, String shapeName) {
        List<String> violations = new ArrayList<>();
        require(config.getRoleArn(), shapeName, "roleARN", violations);
        require(config.getClusterJdbcUrl(), shapeName, "clusterJDBCURL", violations);
        if (config.getCopyCommand() == null) {
            violations.add(violation(shapeName, "copyCommand", "Member must not be null"));
        }
        if (config.getS3() == null) {
            violations.add(violation(shapeName, "s3Configuration", "Member must not be null"));
        } else {
            requireS3Locators(config.getS3(), shapeName, "s3Configuration", violations);
        }
        if (config.getS3Backup() != null) {
            requireS3Locators(config.getS3Backup(), shapeName, "s3BackupConfiguration", violations);
        }
        if ("Enabled".equals(config.getS3BackupMode()) && config.getS3Backup() == null) {
            violations.add(violation(shapeName, "s3BackupConfiguration",
                    "Member must not be null when S3BackupMode is Enabled"));
        }
        collectMemberViolations(config, shapeName, violations);
        throwIfAny(violations);
        S3DestinationValidator.validateWireShape(config.getS3(), shapeName + ".s3Configuration");
        if (config.getS3Backup() != null) {
            S3DestinationValidator.validateWireShape(config.getS3Backup(), shapeName + ".s3BackupConfiguration");
        }
    }

    static void validateUpdate(RedshiftDestination update, String shapeName) {
        List<String> violations = new ArrayList<>();
        collectMemberViolations(update, shapeName, violations);
        throwIfAny(violations);
        if (update.getS3() != null) {
            S3DestinationValidator.validateWireShape(update.getS3(), shapeName + ".s3Update");
        }
        if (update.getS3Backup() != null) {
            S3DestinationValidator.validateWireShape(update.getS3Backup(), shapeName + ".s3BackupUpdate");
        }
    }

    /**
     * A backup location an update supplies when none is stored yet is a whole new S3 configuration, so its required
     * members must be present: the merge has nothing to take them from.
     */
    static void validateNewBackup(S3Destination backup, String shapeName) {
        List<String> violations = new ArrayList<>();
        requireS3Locators(backup, shapeName, "s3BackupUpdate", violations);
        throwIfAny(violations);
    }

    /** Backup can be switched on after creation but never off again. */
    static void validateBackupTransition(String storedMode, String requestedMode) {
        if ("Enabled".equals(storedMode) && "Disabled".equals(requestedMode)) {
            throw new AwsException("InvalidArgumentException",
                    "S3 backup cannot be disabled once it is enabled.", 400);
        }
    }

    private static void collectMemberViolations(RedshiftDestination dest, String shape, List<String> violations) {
        String roleArn = dest.getRoleArn();
        if (roleArn != null) {
            checkLength(roleArn, 1, 512, shape, "roleARN", violations);
            checkPattern(roleArn, ROLE_ARN, ROLE_ARN_REGEX, shape, "roleARN", violations);
        }
        String url = dest.getClusterJdbcUrl();
        if (url != null) {
            checkLength(url, 1, 512, shape, "clusterJDBCURL", violations);
            checkPattern(url, JDBC_URL, JDBC_URL_REGEX, shape, "clusterJDBCURL", violations);
        }
        if (dest.getUsername() != null) {
            checkLength(dest.getUsername(), 1, 512, shape, "username", violations);
        }
        if (dest.getPassword() != null) {
            checkLength(dest.getPassword(), 6, 512, shape, "password", violations);
        }
        RedshiftDestination.CopyCommand copy = dest.getCopyCommand();
        if (copy != null) {
            // DataTableName is required inside CopyCommand on update too: the merge replaces the whole structure.
            require(copy.getDataTableName(), shape, "copyCommand.dataTableName", violations);
            if (copy.getDataTableName() != null) {
                checkLength(copy.getDataTableName(), 1, 512, shape, "copyCommand.dataTableName", violations);
                if (!RedshiftCopySql.isValidTableName(copy.getDataTableName())) {
                    violations.add(violation(shape, "copyCommand.dataTableName",
                            "Member must be a table name, optionally schema-qualified"));
                }
            }
            if (copy.getCopyOptions() != null) {
                checkLength(copy.getCopyOptions(), 0, 10240, shape, "copyCommand.copyOptions", violations);
                if (RedshiftCopySql.hasStatementSeparator(copy.getCopyOptions())) {
                    violations.add(violation(shape, "copyCommand.copyOptions",
                            "Member must not contain ';', '$', '--' or '/*' outside a quoted value"));
                }
            }
            if (copy.getDataTableColumns() != null) {
                checkLength(copy.getDataTableColumns(), 0, 10240, shape, "copyCommand.dataTableColumns", violations);
                if (!RedshiftCopySql.isValidColumnList(copy.getDataTableColumns())) {
                    violations.add(violation(shape, "copyCommand.dataTableColumns",
                            "Member must be a comma-separated list of column names"));
                }
            }
        }
        RedshiftDestination.RetryOptions retry = dest.getRetryOptions();
        if (retry != null && retry.getDurationInSeconds() != null
                && (retry.getDurationInSeconds() < 0 || retry.getDurationInSeconds() > 7200)) {
            violations.add(violation(shape, "retryOptions.durationInSeconds",
                    "Member must have value between 0 and 7200"));
        }
        if (dest.getS3BackupMode() != null && !BACKUP_MODES.contains(dest.getS3BackupMode())) {
            violations.add(violation(shape, "s3BackupMode", "Member must satisfy enum value set: [Disabled, Enabled]"));
        }
        RedshiftDestination.SecretsManagerConfiguration secrets = dest.getSecretsManagerConfiguration();
        if (secrets != null) {
            if (secrets.getEnabled() == null) {
                violations.add(violation(shape, "secretsManagerConfiguration.enabled", "Member must not be null"));
            }
            if (Boolean.TRUE.equals(secrets.getEnabled()) && secrets.getSecretArn() == null) {
                violations.add(violation(shape, "secretsManagerConfiguration.secretARN",
                        "Member must not be null when Enabled is true"));
            }
            if (secrets.getRoleArn() != null) {
                checkLength(secrets.getRoleArn(), 1, 512, shape, "secretsManagerConfiguration.roleARN", violations);
                checkPattern(secrets.getRoleArn(), ROLE_ARN, ROLE_ARN_REGEX, shape,
                        "secretsManagerConfiguration.roleARN", violations);
            }
            if (secrets.getSecretArn() != null) {
                checkLength(secrets.getSecretArn(), 1, 2048, shape, "secretsManagerConfiguration.secretARN", violations);
                checkPattern(secrets.getSecretArn(), SECRET_ARN, SECRET_ARN_REGEX, shape,
                        "secretsManagerConfiguration.secretARN", violations);
            }
        }
        S3Destination s3 = dest.getS3();
        if (s3 != null && s3.getPrefix() != null && UNREADABLE_KEY_CHARS.matcher(s3.getPrefix()).find()) {
            violations.add(violation(shape, "s3Configuration.prefix",
                    "Member must not contain whitespace or quotes: the Redshift COPY emulation cannot read such object keys"));
        }
        if (s3 != null && s3.getCompressionFormat() != null
                && UNSUPPORTED_COMPRESSION.contains(s3.getCompressionFormat().toLowerCase(Locale.ROOT))) {
            violations.add(violation(shape, "s3Configuration.compressionFormat",
                    "Member must not be SNAPPY or ZIP: the Amazon Redshift COPY command cannot read these formats"));
        }
    }

    /** The bucket and role an S3 configuration cannot do without: with no bucket, staging falls back to a shared one. */
    private static void requireS3Locators(S3Destination s3, String shape, String member, List<String> violations) {
        require(s3.getBucketArn(), shape, member + ".bucketARN", violations);
        require(s3.getRoleArn(), shape, member + ".roleARN", violations);
    }

    private static void require(String value, String shape, String member, List<String> violations) {
        if (value == null) {
            violations.add(violation(shape, member, "Member must not be null"));
        }
    }

    private static void checkLength(String value, int min, int max, String shape, String member,
                                    List<String> violations) {
        if (value.length() < min) {
            violations.add(violation(shape, member, "Member must have length greater than or equal to " + min));
        } else if (value.length() > max) {
            violations.add(violation(shape, member, "Member must have length less than or equal to " + max));
        }
    }

    private static void checkPattern(String value, Pattern pattern, String regex, String shape, String member,
                                     List<String> violations) {
        if (!pattern.matcher(value).matches()) {
            violations.add(violation(shape, member, "Member must satisfy regular expression pattern: " + regex));
        }
    }

    private static String violation(String shape, String member, String constraint) {
        return "Value at '" + shape + "." + member + "' failed to satisfy constraint: " + constraint;
    }

    private static void throwIfAny(List<String> violations) {
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
    }
}
