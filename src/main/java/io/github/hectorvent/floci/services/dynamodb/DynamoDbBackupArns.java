package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backup ARNs: {@code <table ARN>/backup/<14-digit epoch millis>-<8 hex digits>}.
 */
final class DynamoDbBackupArns {

    private static final Pattern RESOURCE = Pattern.compile("table/([a-zA-Z0-9_.\\-]{3,255})/backup/([^/]{3,})");
    private static final Pattern BACKUP_ID = Pattern.compile("\\d{14}-[0-9a-fA-F]{8}");

    private DynamoDbBackupArns() {}

    record BackupArn(String arn, String partition, String service, String region, String accountId,
                     String tableName, String backupId) {

        /** AWS answers "Invalid Request: BackupArn is not valid" when this fails. */
        boolean isValid() {
            return "dynamodb".equals(service) && AwsRegions.isRegionId(region)
                    && AwsRegions.partitionFor(region).equals(partition) && !accountId.isEmpty()
                    && BACKUP_ID.matcher(backupId).matches();
        }

        long creationMillis() {
            return Long.parseLong(backupId.substring(0, 14));
        }
    }

    /**
     * AWS answers "Invalid Backup ARN" when it cannot split the input into the ARN fields. It checks
     * the account right after this, before the rest of the ARN.
     */
    static AwsArnUtils.Arn split(String input) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(input);
        } catch (IllegalArgumentException e) {
            throw invalidBackupArn();
        }
        if (arn.partition().isEmpty() || arn.service().isEmpty() || arn.resource().isEmpty()) {
            throw invalidBackupArn();
        }
        return arn;
    }

    /** AWS answers "Invalid Backup ARN" when the resource is not a table and a backup id. */
    static BackupArn parse(String input) {
        AwsArnUtils.Arn arn = split(input);
        Matcher resource = RESOURCE.matcher(arn.resource());
        if (!resource.matches()) {
            throw invalidBackupArn();
        }
        return new BackupArn(input, arn.partition(), arn.service(), arn.region(), arn.accountId(),
                resource.group(1), resource.group(2));
    }

    static String mint(String tableArn, Instant creationDateTime) {
        return tableArn + "/backup/" + timedId(creationDateTime);
    }

    /** AWS ids a backup and an export by their time in milliseconds and 8 random hex digits. */
    static String timedId(Instant time) {
        return String.format("%014d", time.toEpochMilli()) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** AWS ends the id of a system backup with zeros instead of random hex digits. */
    static String mintSystem(String tableArn, Instant creationDateTime) {
        return tableArn + "/backup/" + String.format("%014d", creationDateTime.toEpochMilli()) + "-00000000";
    }

    private static AwsException invalidBackupArn() {
        return new AwsException("ValidationException", "Invalid Backup ARN", 400);
    }
}
