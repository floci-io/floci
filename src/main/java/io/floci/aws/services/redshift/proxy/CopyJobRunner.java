package io.floci.aws.services.redshift.proxy;

import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.redshift.proxy.S3CopySimulator.S3TransferException;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Checkpoints and target rows are written on the same backend transaction. */
final class CopyJobRunner {
    private record FileRevision(String etag, String versionId) {
        boolean matches(S3Object object) {
            return Objects.equals(etag, object.getETag()) && Objects.equals(versionId, object.getVersionId());
        }
    }

    private CopyJobRunner() {
    }

    static long load(CopyBackendSession backend, String name, S3Service s3, IamService iam,
                     String account, List<String> roles) throws IOException {
        List<List<String>> jobs = backend.query("SELECT job_id, job_text, bucket, prefix, "
                + "extract(epoch FROM created_at), owner FROM floci_internal.copy_job_for_run("
                + PostgresWire.quoteLiteral(name) + ")");
        if (jobs.isEmpty()) {
            throw new S3TransferException("42704", "Copy job does not exist: " + name, null);
        }
        List<String> job = jobs.getFirst();
        CopyStatementParser.S3Statement parsed = CopyStatementParser.parse(job.get(1));
        if (!(parsed instanceof CopyStatementParser.S3CopyFrom copy)) {
            throw new S3TransferException("0A000", "Unsupported COPY options in job " + name, null);
        }
        RedshiftRoleAccess.RoleSession session = RedshiftRoleAccess.resolveRoleSession(copy.iamRoleArn(), iam, account, roles);
        try {
            RedshiftRoleAccess.authorizeRoleList(s3, iam, session, copy.iamRoleArn(), copy.bucket(), copy.keyOrPrefix());
            String caller = backend.query("SELECT current_user").getFirst().getFirst();
            backend.execute("SET LOCAL ROLE " + identifier(job.get(5)));
            long count = loadFiles(backend, job, copy, s3, iam, account, roles);
            backend.execute("SET LOCAL ROLE " + identifier(caller));
            return count;
        } finally {
            RedshiftRoleAccess.releaseRoleSession(session, copy.iamRoleArn(), iam);
        }
    }

    private static long loadFiles(CopyBackendSession backend, List<String> job, CopyStatementParser.S3CopyFrom copy,
                                  S3Service s3, IamService iam, String account, List<String> roles) throws IOException {
        long id = Long.parseLong(job.get(0));
        // S3 stores lastModified at millisecond precision.
        Instant created = Instant.ofEpochMilli(new BigDecimal(job.get(4)).movePointRight(3).longValue());
        Set<String> checkpointed = new HashSet<>();
        Map<String, FileRevision> failedFiles = new HashMap<>();
        for (List<String> row : backend.query(
                "SELECT key, status, etag, version_id FROM floci_internal.copy_job_loaded_keys(" + id + ")")) {
            if (row.isEmpty() || row.getFirst() == null) {
                continue;
            }
            String status = row.size() > 1 && row.get(1) != null ? row.get(1) : "I";
            if ("E".equals(status)) {
                failedFiles.put(row.getFirst(), new FileRevision(
                        row.size() > 2 ? row.get(2) : null,
                        row.size() > 3 ? row.get(3) : null));
            } else {
                checkpointed.add(row.getFirst());
            }
        }
        long count = 0;
        String token = null;
        do {
            S3Service.ListObjectsResult page = s3.listObjectsWithPrefixes(job.get(2), job.get(3), null, 1000, token, null);
            for (S3Object object : page.objects()) {
                if (object.getLastModified() != null && object.getLastModified().isBefore(created)) {
                    continue;
                }
                if (checkpointed.contains(object.getKey())) {
                    continue;
                }
                FileRevision previousFailure = failedFiles.get(object.getKey());
                if (previousFailure != null && previousFailure.matches(object)) {
                    continue;
                }
                String fileSavepoint = "floci_job_file_" + UUID.randomUUID().toString().replace("-", "");
                backend.execute("SAVEPOINT " + fileSavepoint);
                try {
                    String checkpoint = "SELECT floci_internal.reserve_copy_job_file("
                            + fileMetadataArgs(id, object) + ")";
                    if (!"t".equals(backend.query(checkpoint).getFirst().getFirst())) {
                        backend.execute("RELEASE SAVEPOINT " + fileSavepoint);
                        checkpointed.add(object.getKey());
                        continue;
                    }
                    count += CopyEtlLoader.executeObject(backend, forObject(copy, object.getKey()),
                            object.getETag(), object.getVersionId(), s3, iam, account, roles);
                    backend.execute("RELEASE SAVEPOINT " + fileSavepoint);
                    checkpointed.add(object.getKey());
                    failedFiles.remove(object.getKey());
                } catch (IOException | RuntimeException exception) {
                    rollbackFileSavepoint(backend, fileSavepoint, exception);
                    if (exception instanceof S3TransferException transfer && S3TransferException.isJobFailure(transfer.sqlState())) {
                        throw transfer;
                    }
                    backend.query("SELECT floci_internal.record_copy_job_file_error("
                            + fileMetadataArgs(id, object) + ")");
                    failedFiles.put(object.getKey(), new FileRevision(object.getETag(), object.getVersionId()));
                }
            }
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
        return count;
    }

    private static String fileMetadataArgs(long id, S3Object object) {
        String modified = object.getLastModified() == null
                ? "NULL"
                : PostgresWire.quoteLiteral(object.getLastModified().toString()) + "::timestamptz";
        return id + ", " + PostgresWire.quoteLiteral(object.getKey()) + ", "
                + literal(object.getETag()) + ", " + literal(object.getVersionId()) + ", "
                + object.getSize() + ", " + modified;
    }

    private static void rollbackFileSavepoint(CopyBackendSession backend, String savepoint,
                                              Exception cause) throws IOException {
        try {
            backend.recover();
            backend.execute("ROLLBACK TO SAVEPOINT " + savepoint);
            backend.execute("RELEASE SAVEPOINT " + savepoint);
        } catch (IOException | RuntimeException recoveryFailure) {
            IOException failure = new IOException("Unable to recover failed COPY job file", recoveryFailure);
            failure.addSuppressed(cause);
            throw failure;
        }
    }

    static CopyStatementParser.S3CopyFrom forObject(CopyStatementParser.S3CopyFrom copy, String key) {
        return new CopyStatementParser.S3CopyFrom(copy.targetTable(), copy.columns(), copy.bucket(), key,
                copy.delimiter(), copy.headerLines(), copy.gzip(), copy.csv(), copy.nullAs(), copy.iamRoleArn(),
                copy.jsonAuto(), copy.jsonAutoIgnoreCase(), copy.manifest(), copy.transforms(),
                copy.defaultIamRoleRequested(), copy.accessKeys());
    }

    private static String literal(String value) {
        return value == null ? "NULL" : PostgresWire.quoteLiteral(value);
    }

    private static String identifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
