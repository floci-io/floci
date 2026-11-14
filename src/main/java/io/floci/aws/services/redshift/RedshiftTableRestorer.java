package io.floci.aws.services.redshift;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.redshift.container.RedshiftContainerManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                containerManager.restoreSnapshot(accountId, backendId, masterUsername, database, file, true);
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
