package io.floci.aws.services.redshift.proxy;

import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.redshift.proxy.S3CopySimulator.S3TransferException;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Checkpoints and target rows are written on the same backend transaction. */
final class CopyJobRunner {
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
        long count = 0;
        String token = null;
        do {
            S3Service.ListObjectsResult page = s3.listObjectsWithPrefixes(job.get(2), job.get(3), null, 1000, token, null);
            for (S3Object object : page.objects()) {
                if (object.getLastModified() != null && object.getLastModified().isBefore(created)) {
                    continue;
                }
                String checkpoint = "SELECT floci_internal.reserve_copy_job_file(" + id + ", "
                        + PostgresWire.quoteLiteral(object.getKey()) + ", "
                        + literal(object.getETag()) + ", " + literal(object.getVersionId()) + ")";
                if (!"t".equals(backend.query(checkpoint).getFirst().getFirst())) {
                    continue;
                }
                count += CopyEtlLoader.executeObject(backend, forObject(copy, object.getKey()), s3, iam, account, roles);
            }
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
        return count;
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
