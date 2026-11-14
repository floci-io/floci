package io.floci.aws.services.redshift;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.redshift.container.RedshiftContainerManager;
import io.floci.aws.services.redshift.model.TableRestoreStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Restores one table of a snapshot dump into a running backend, for Redshift and Redshift Serverless alike.
 * The caller decides what an unknown source table means, since the two APIs report it differently.
 */
@ApplicationScoped
public class RedshiftTableRestorer {

    private static final Logger LOG = Logger.getLogger(RedshiftTableRestorer.class);

    private final RedshiftContainerManager containerManager;

    @Inject
    public RedshiftTableRestorer(RedshiftContainerManager containerManager) {
        this.containerManager = containerManager;
    }

    private static final long MEGABYTE = 1024L * 1024L;

    /**
     * The names of one table restore request. They are case insensitive unless the request says otherwise, which
     * Postgres folds to lower case; {@code applyTo} reports them as the request gave them.
     */
    public record TableNames(String sourceDatabaseName, String sourceSchemaName, String sourceTableName,
                             String targetDatabaseName, String targetSchemaName, String newTableName,
                             boolean caseSensitive) {

        public String sourceDatabase() {
            return fold(sourceDatabaseName);
        }

        public String sourceSchema() {
            return fold(orDefault(sourceSchemaName, "public"));
        }

        public String sourceTable() {
            return fold(sourceTableName);
        }

        public String targetDatabase() {
            return fold(orDefault(targetDatabaseName, sourceDatabaseName));
        }

        public String targetSchema() {
            return fold(orDefault(targetSchemaName, sourceSchema()));
        }

        public String newTable() {
            return fold(newTableName);
        }

        /** Whether {@code actual}, the one database of the backend, is the source database the request names. */
        public boolean sourceDatabaseIs(String actual) {
            return sourceDatabase().equals(fold(actual));
        }

        /** Whether {@code actual}, the one database of the backend, is the target database the request names. */
        public boolean targetDatabaseIs(String actual) {
            return targetDatabase().equals(fold(actual));
        }

        public void applyTo(TableRestoreStatus status) {
            status.setSourceDatabaseName(sourceDatabaseName);
            status.setSourceSchemaName(orDefault(sourceSchemaName, "public"));
            status.setSourceTableName(sourceTableName);
            status.setTargetDatabaseName(orDefault(targetDatabaseName, sourceDatabaseName));
            status.setTargetSchemaName(orDefault(targetSchemaName, status.getSourceSchemaName()));
            status.setNewTableName(newTableName);
        }

        private String fold(String name) {
            return caseSensitive ? name : name.toLowerCase(Locale.ROOT);
        }

        private static String orDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    /** The size of {@code script} in whole megabytes, which a status reports as the data to restore. */
    public static long megabytes(String script) {
        return script.length() / MEGABYTE;
    }

    /** The script that restores the table under its new name, or empty when the dump has no such table. */
    public Optional<String> script(Path dump, String sourceSchema, String sourceTable, String targetSchema,
                                   String newTableName, boolean caseSensitive) {
        try {
            return RedshiftSnapshotTableExtractor.restoreScript(dump, sourceSchema, sourceTable, targetSchema,
                    newTableName, caseSensitive);
        } catch (IOException e) {
            LOG.warnv(e, "Could not read the snapshot dump {0}", dump);
            throw new AwsException("InternalFailure", "The data of the snapshot could not be read: " + e.getMessage(),
                    500);
        }
    }

    /** The psql error line alone: its CONTEXT lines carry the rows that failed. */
    private static String reason(String message) {
        if (message != null) {
            for (String line : message.split("\\R")) {
                int at = line.indexOf("ERROR:");
                if (at >= 0) {
                    String reason = line.substring(at + "ERROR:".length()).strip();
                    return reason.length() > 300 ? reason.substring(0, 300) : reason;
                }
            }
        }
        return "the script failed.";
    }

    /**
     * Runs {@code script} in {@code database} of the backend. It is one transaction, so a failing statement leaves
     * nothing behind.
     */
    public void run(String accountId, String backendId, String masterUsername, String database, String script) {
        Path file;
        try {
            file = Files.createTempFile("redshift-table-restore-", ".sql");
            Files.writeString(file, script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AwsException("InternalFailure", "The table restore could not be prepared: " + e.getMessage(),
                    500);
        }
        try {
            try {
                containerManager.runTableRestoreScript(accountId, backendId, masterUsername, database, file);
            } catch (AwsException e) {
                throw new AwsException("InternalFailure", "The table could not be restored: " + reason(e.getMessage()), 500);
            }
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.warnv(e, "Could not delete the table restore script {0}", file);
            }
        }
    }
}
