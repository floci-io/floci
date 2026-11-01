package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftDestinationValidatorTest {

    private static final String SHAPE = "redshiftDestinationConfiguration";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private RedshiftDestination valid() throws Exception {
        return mapper.readValue("""
                {
                  "RoleARN": "arn:aws:iam::000000000000:role/firehose",
                  "ClusterJDBCURL": "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev",
                  "Username": "admin", "Password": "Secret123",
                  "CopyCommand": {"DataTableName": "events"},
                  "S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose", "BucketARN": "arn:aws:s3:::stage"}
                }
                """, RedshiftDestination.class);
    }

    private static String messageOf(RedshiftDestination dest) {
        AwsException e = assertThrows(AwsException.class,
                () -> RedshiftDestinationValidator.validateConfiguration(dest, SHAPE));
        return e.getMessage();
    }

    @Test
    void validConfigurationPasses() throws Exception {
        RedshiftDestination dest = valid();

        assertDoesNotThrow(() -> RedshiftDestinationValidator.validateConfiguration(dest, SHAPE));
    }

    @Test
    void missingRequiredMembersAreReported() throws Exception {
        RedshiftDestination dest = valid();
        dest.setRoleArn(null);
        dest.setClusterJdbcUrl(null);
        dest.setCopyCommand(null);
        dest.setS3(null);

        String message = messageOf(dest);

        assertTrue(message.contains("redshiftDestinationConfiguration.roleARN"), message);
        assertTrue(message.contains("redshiftDestinationConfiguration.clusterJDBCURL"), message);
        assertTrue(message.contains("redshiftDestinationConfiguration.copyCommand"), message);
        assertTrue(message.contains("redshiftDestinationConfiguration.s3Configuration"), message);
    }

    @Test
    void intermediateConfigurationWithoutBucketOrRoleIsRejected() throws Exception {
        RedshiftDestination dest = valid();
        dest.getS3().setBucketArn(null);
        dest.getS3().setRoleArn(null);

        String message = messageOf(dest);

        assertTrue(message.contains("redshiftDestinationConfiguration.s3Configuration.bucketARN"), message);
        assertTrue(message.contains("redshiftDestinationConfiguration.s3Configuration.roleARN"), message);
    }

    @Test
    void backupConfigurationWithoutABucketIsRejected() throws Exception {
        RedshiftDestination dest = valid();
        dest.setS3BackupMode("Enabled");
        S3Destination backup = new S3Destination();
        backup.setRoleArn("arn:aws:iam::000000000000:role/firehose");
        dest.setS3Backup(backup);

        assertTrue(messageOf(dest).contains("s3BackupConfiguration.bucketARN"));
    }

    @Test
    void secretsManagerRoleMustBeAnIamRoleArn() throws Exception {
        RedshiftDestination dest = valid();
        RedshiftDestination.SecretsManagerConfiguration secrets = new RedshiftDestination.SecretsManagerConfiguration();
        secrets.setEnabled(false);
        secrets.setRoleArn("not-an-arn");
        dest.setSecretsManagerConfiguration(secrets);

        assertTrue(messageOf(dest).contains("secretsManagerConfiguration.roleARN"));
    }

    @Test
    void jdbcUrlNotMatchingAwsPatternIsRejected() throws Exception {
        RedshiftDestination dest = valid();
        dest.setClusterJdbcUrl("jdbc:postgresql://localhost:5432/dev");

        assertTrue(messageOf(dest).contains("clusterJDBCURL"));
    }

    @Test
    void serverlessAndPostgresqlSchemeUrlsAreAccepted() throws Exception {
        RedshiftDestination dest = valid();
        dest.setClusterJdbcUrl("jdbc:postgresql://wg.123456789012.us-east-1.redshift-serverless.amazonaws.com:5439/dev");

        assertDoesNotThrow(() -> RedshiftDestinationValidator.validateConfiguration(dest, SHAPE));
    }

    @Test
    void passwordShorterThanSixIsRejected() throws Exception {
        RedshiftDestination dest = valid();
        dest.setPassword("short");

        assertTrue(messageOf(dest).contains("password"));
    }

    @Test
    void snappyAndZipAreRejectedOnTheIntermediateLocation() throws Exception {
        for (String format : new String[] {"ZIP", "Snappy"}) {
            RedshiftDestination dest = valid();
            dest.getS3().setCompressionFormat(format);

            assertTrue(messageOf(dest).contains("s3Configuration.compressionFormat"), format);
        }
    }

    @Test
    void retryDurationOutOfRangeIsRejected() throws Exception {
        RedshiftDestination dest = valid();
        RedshiftDestination.RetryOptions retry = new RedshiftDestination.RetryOptions();
        retry.setDurationInSeconds(7201);
        dest.setRetryOptions(retry);

        assertTrue(messageOf(dest).contains("retryOptions.durationInSeconds"));
    }

    @Test
    void secretsManagerEnabledRequiresSecretArn() throws Exception {
        RedshiftDestination dest = valid();
        RedshiftDestination.SecretsManagerConfiguration secrets = new RedshiftDestination.SecretsManagerConfiguration();
        secrets.setEnabled(true);
        dest.setSecretsManagerConfiguration(secrets);

        assertTrue(messageOf(dest).contains("secretsManagerConfiguration.secretARN"));
    }

    @Test
    void secretArnAcceptsEveryCharacterTheModelAllows() throws Exception {
        RedshiftDestination dest = valid();
        dest.setUsername(null);
        dest.setPassword(null);
        RedshiftDestination.SecretsManagerConfiguration secrets = new RedshiftDestination.SecretsManagerConfiguration();
        secrets.setEnabled(true);
        secrets.setSecretArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:team/db!creds-AbCdEf");
        dest.setSecretsManagerConfiguration(secrets);

        assertDoesNotThrow(() -> RedshiftDestinationValidator.validateConfiguration(dest, SHAPE));
    }

    @Test
    void invalidBackupModeAndDisablingBackupAreRejected() throws Exception {
        RedshiftDestination dest = valid();
        dest.setS3BackupMode("Maybe");
        assertTrue(messageOf(dest).contains("s3BackupMode"));

        assertThrows(AwsException.class, () -> RedshiftDestinationValidator.validateBackupTransition("Enabled", "Disabled"));
        assertDoesNotThrow(() -> RedshiftDestinationValidator.validateBackupTransition("Disabled", "Enabled"));
        assertDoesNotThrow(() -> RedshiftDestinationValidator.validateBackupTransition(null, "Disabled"));
    }

    @Test
    void updateChecksOnlyPresentMembers() {
        RedshiftDestination update = new RedshiftDestination();
        update.setUsername("other");

        assertDoesNotThrow(() -> RedshiftDestinationValidator.validateUpdate(update, "redshiftDestinationUpdate"));
    }

    @Test
    void tableNameAndColumnsMustBeIdentifiersTheProxyUnderstands() throws Exception {
        RedshiftDestination dest = valid();
        dest.getCopyCommand().setDataTableName("a.b.c");
        assertTrue(messageOf(dest).contains("copyCommand.dataTableName"));

        RedshiftDestination columns = valid();
        columns.getCopyCommand().setDataTableColumns("id, name); DROP TABLE t");
        assertTrue(messageOf(columns).contains("copyCommand.dataTableColumns"));
    }

    @Test
    void copyOptionsSeparatorIsRejectedOnlyOutsideQuotes() throws Exception {
        RedshiftDestination bad = valid();
        bad.getCopyCommand().setCopyOptions("gzip; DROP TABLE events");
        assertTrue(messageOf(bad).contains("copyCommand.copyOptions"));

        RedshiftDestination fine = valid();
        fine.getCopyCommand().setCopyOptions("DELIMITER ';' CSV");
        assertDoesNotThrow(() -> RedshiftDestinationValidator.validateConfiguration(fine, SHAPE));
    }

    @Test
    void updateCopyCommandStillNeedsATableName() {
        RedshiftDestination update = new RedshiftDestination();
        RedshiftDestination.CopyCommand command = new RedshiftDestination.CopyCommand();
        command.setCopyOptions("CSV");
        update.setCopyCommand(command);

        AwsException e = assertThrows(AwsException.class,
                () -> RedshiftDestinationValidator.validateUpdate(update, "redshiftDestinationUpdate"));

        assertTrue(e.getMessage().contains("copyCommand.dataTableName"), e.getMessage());
    }

    @Test
    void backupEnabledWithoutABackupConfigurationIsRejected() throws Exception {
        RedshiftDestination dest = valid();
        dest.setS3BackupMode("Enabled");

        assertTrue(messageOf(dest).contains("s3BackupConfiguration"));
    }

    @Test
    void intermediatePrefixWithWhitespaceOrQuotesIsRejected() throws Exception {
        for (String prefix : new String[] {"my prefix/", "it's/", "say\"/"}) {
            RedshiftDestination dest = valid();
            dest.getS3().setPrefix(prefix);

            assertTrue(messageOf(dest).contains("s3Configuration.prefix"), prefix);
        }
    }
}
