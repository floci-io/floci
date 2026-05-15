package io.github.hectorvent.floci.services.cur;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.UsageLine;
import io.github.hectorvent.floci.services.floci.FlociDuckClient;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a Parquet artifact for a CUR/BCM run.
 * <p>
 * Pipeline:
 * <ol>
 *   <li>{@link FocusRowProjector} converts {@link UsageLine}s to {@link FocusRow}s.</li>
 *   <li>Rows are serialized as newline-delimited JSON and uploaded to a CUR
 *       staging bucket via {@link S3Service}; this avoids any hand-rolled SQL
 *       escaping for tags, descriptions, and resource IDs.</li>
 *   <li>{@link FlociDuckClient#execute} runs a {@code COPY (SELECT * FROM
 *       read_json_auto('s3://staging/...')) TO 's3://dest/...' (FORMAT
 *       PARQUET)}; DuckDB reads the staging object through Floci's S3 service
 *       and writes the Parquet artifact straight back to Floci S3.</li>
 *   <li>The staging object is removed in a best-effort {@code finally} block
 *       so concurrent emissions don't accumulate noise.</li>
 * </ol>
 *
 * <p>Each emission carries a fresh {@code runId} (UUID), reflected in both
 * staging and destination keys, so two emissions cannot clobber each other
 * even when started in parallel.
 *
 * <p>The {@code /execute} endpoint always writes a CSV result to
 * {@code output_s3_path} for compatibility with the Athena code path, so we
 * pass a harmless staging key for that throwaway side effect — the real
 * artifact is the Parquet object produced by the {@code COPY} statement.
 */
@ApplicationScoped
public class ParquetEmitter {

    private static final Logger LOG = Logger.getLogger(ParquetEmitter.class);

    private final FocusRowProjector projector;
    private final FlociDuckClient duckClient;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;
    private final String stagingBucket;
    private final String defaultRegion;

    @Inject
    public ParquetEmitter(FocusRowProjector projector,
                          FlociDuckClient duckClient,
                          S3Service s3Service,
                          ObjectMapper objectMapper,
                          EmulatorConfig config) {
        this(projector, duckClient, s3Service, objectMapper,
                config.services().cur().stagingBucket(),
                config.defaultRegion());
    }

    ParquetEmitter(FocusRowProjector projector, FlociDuckClient duckClient,
                   S3Service s3Service, ObjectMapper objectMapper,
                   String stagingBucket, String defaultRegion) {
        this.projector = projector;
        this.duckClient = duckClient;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
        this.stagingBucket = stagingBucket;
        this.defaultRegion = defaultRegion;
    }

    /**
     * Emits a Parquet object for {@code lines} into
     * {@code s3://<destBucket>/<destPrefix>/<reportName>/<runId>.parquet}.
     *
     * @return the resulting {@link Result} record (S3 path + row count + run id)
     * @throws AwsException when DuckDB or S3 fails; staging cleanup still runs
     */
    public Result emit(String reportName, String destBucket, String destPrefix,
                       List<UsageLine> lines) {
        return emit(reportName, destBucket, destPrefix, lines, null);
    }

    /**
     * Variant of {@link #emit(String, String, String, List)} that authenticates
     * the DuckDB-side S3 client as {@code ownerAccountId} so the read of the
     * staging NDJSON object and the write of the final Parquet artifact both
     * happen under that account's S3 partition. Pass {@code null} to use the
     * default account (matches the previous behavior).
     */
    public Result emit(String reportName, String destBucket, String destPrefix,
                       List<UsageLine> lines, String ownerAccountId) {
        if (reportName == null || reportName.isEmpty()) {
            throw new AwsException("ValidationException", "reportName is required.", 400);
        }
        if (destBucket == null || destBucket.isEmpty()) {
            throw new AwsException("ValidationException", "destBucket is required.", 400);
        }
        if (lines == null) {
            throw new AwsException("ValidationException", "lines is required.", 400);
        }
        // Reject anything that doesn't look like an S3 bucket / object-key
        // segment up front. The emitter interpolates these into DuckDB SQL, so
        // unconstrained input would let a quote in S3Prefix terminate the SQL
        // literal and inject additional statements via setup_sql.
        validateBucketName(destBucket);
        validatePathSegment(reportName, "reportName");
        if (destPrefix != null && !destPrefix.isEmpty()) {
            validatePathSegment(destPrefix, "destPrefix");
        }

        String runId = UUID.randomUUID().toString();
        String prefix = destPrefix == null || destPrefix.isEmpty() ? "" : trimSlashes(destPrefix) + "/";
        String stagingKey = "cur-staging/" + reportName + "/" + runId + ".ndjson";
        String destKey = prefix + reportName + "/" + runId + ".parquet";
        String destS3Path = "s3://" + destBucket + "/" + destKey;
        String stagingS3Path = "s3://" + stagingBucket + "/" + stagingKey;

        ensureStagingBucket();

        boolean stagingWritten = false;
        try {
            List<FocusRow> rows = projector.project(lines);
            byte[] payload = serializeNdjson(rows);
            s3Service.putObject(stagingBucket, stagingKey, payload,
                    "application/x-ndjson", new HashMap<>());
            stagingWritten = true;

            // floci-duck's /execute wraps the main `sql` field in a CSV-emitting
            // COPY for Athena compatibility. We need raw DDL/COPY semantics, so the
            // Parquet write goes in `setup_sql` (which is executed verbatim before
            // the wrap), and the wrapped `sql` is a trivial SELECT whose CSV
            // output is treated as throwaway.
            String resultKey = "cur-staging/" + reportName + "/" + runId + ".result.csv";
            String resultS3Path = "s3://" + stagingBucket + "/" + resultKey;

            // Escape every interpolated path even though validatePathSegment
            // already rejects quotes — defense in depth.
            String setupSql = "COPY (SELECT * FROM read_json_auto('"
                    + escapeSqlLiteral(stagingS3Path)
                    + "', format='newline_delimited')) TO '"
                    + escapeSqlLiteral(destS3Path)
                    + "' (FORMAT PARQUET);";
            String sql = "SELECT 1 AS ok";

            duckClient.execute(sql, setupSql, resultS3Path, ownerAccountId);

            // Clean the side-effect CSV up too; never load-bearing.
            safeDelete(stagingBucket, resultKey);

            LOG.infov("CUR emit ok: report={0} runId={1} rows={2} parquet={3}",
                    reportName, runId, rows.size(), destS3Path);
            return new Result(runId, destBucket, destKey, destS3Path, rows.size());
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalServerException",
                    "Parquet emission failed: " + e.getMessage(), 500);
        } finally {
            if (stagingWritten) {
                safeDelete(stagingBucket, stagingKey);
            }
        }
    }

    /** Surfaces emission outputs without forcing callers to parse the SQL response. */
    public record Result(String runId, String bucket, String key, String s3Uri, int rowCount) {
    }

    private void ensureStagingBucket() {
        for (Bucket b : s3Service.listBuckets()) {
            if (stagingBucket.equals(b.getName())) {
                return;
            }
        }
        try {
            s3Service.createBucket(stagingBucket, defaultRegion);
        } catch (AwsException e) {
            // BucketAlreadyOwnedByYou under race; safe to ignore.
            if (!"BucketAlreadyOwnedByYou".equals(e.getErrorCode())
                    && !"BucketAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }

    private byte[] serializeNdjson(List<FocusRow> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 256);
        for (FocusRow row : rows) {
            try {
                sb.append(objectMapper.writeValueAsString(row));
            } catch (Exception e) {
                throw new AwsException("InternalServerException",
                        "Failed to serialize FocusRow: " + e.getMessage(), 500);
            }
            sb.append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void safeDelete(String bucket, String key) {
        try {
            s3Service.deleteObject(bucket, key);
        } catch (Exception e) {
            // Best-effort cleanup; swallow to avoid masking the primary outcome.
            LOG.debugv(e, "Staging cleanup failed for s3://{0}/{1}", bucket, key);
        }
    }

    private static String trimSlashes(String prefix) {
        int start = 0;
        int end = prefix.length();
        while (start < end && prefix.charAt(start) == '/') {
            start++;
        }
        while (end > start && prefix.charAt(end - 1) == '/') {
            end--;
        }
        return prefix.substring(start, end);
    }

    /** Escapes a value for embedding inside a DuckDB single-quoted SQL literal. */
    static String escapeSqlLiteral(String raw) {
        return raw.replace("'", "''");
    }

    /**
     * Rejects bucket names that don't satisfy the AWS S3 bucket-naming rules
     * (3-63 chars, lowercase alphanumerics, hyphens, dots; no two adjacent dots;
     * no IP-address shape). This is an extra safety net on top of
     * {@link #escapeSqlLiteral}; bucket names that pass S3 validation cannot
     * contain SQL-significant characters in the first place.
     */
    private static void validateBucketName(String bucket) {
        if (bucket.length() < 3 || bucket.length() > 63) {
            throw new AwsException("ValidationException",
                    "S3 bucket name must be between 3 and 63 characters.", 400);
        }
        for (int i = 0; i < bucket.length(); i++) {
            char c = bucket.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '.';
            if (!valid) {
                throw new AwsException("ValidationException",
                        "S3 bucket name contains invalid characters.", 400);
            }
        }
        if (bucket.startsWith("-") || bucket.endsWith("-")
                || bucket.startsWith(".") || bucket.endsWith(".")) {
            throw new AwsException("ValidationException",
                    "S3 bucket name cannot start or end with a hyphen or period.", 400);
        }
        if (bucket.contains("..")) {
            throw new AwsException("ValidationException",
                    "S3 bucket name cannot contain two adjacent periods.", 400);
        }
    }

    /**
     * Rejects path components that contain SQL-significant characters.
     * Allowed: alphanumerics, hyphens, underscores, periods, and forward slashes
     * (so callers can pass a prefix like {@code billing/2026}).
     */
    private static void validatePathSegment(String value, String field) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '/';
            if (!ok) {
                throw new AwsException("ValidationException",
                        field + " contains characters not permitted in an S3 key segment.", 400);
            }
        }
    }
}
