package io.floci.aws.services.redshift.proxy;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3ScratchSpace;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Converts PostgreSQL CSV slices using the query's declared types, never data inference. */
@ApplicationScoped
public class RedshiftParquetWriter {
    private static final Logger LOG = Logger.getLogger(RedshiftParquetWriter.class);
    static final String STAGING_BUCKET = S3Service.REDSHIFT_UNLOAD_STAGING_BUCKET;
    private final FlociDuckClient duck;
    private final S3Service s3;
    private final IamService iam;
    private final String region;
    private final String defaultAccountId;

    public RedshiftParquetWriter(FlociDuckClient duck, S3Service s3, IamService iam, EmulatorConfig config) {
        this.duck = duck;
        this.s3 = s3;
        this.iam = iam;
        region = config.defaultRegion();
        String configuredAccount = config.defaultAccountId();
        defaultAccountId = configuredAccount != null && !configuredAccount.isBlank()
                ? configuredAccount : "000000000000";
    }

    /** CSV bytes converted from Parquet together with their reservation on {@code COPY_TRANSFORM_HEAP_MIB}. */
    static final class StagedCsv implements AutoCloseable {
        static final StagedCsv EMPTY = new StagedCsv(new byte[0], 0);

        private final byte[] bytes;
        private int heldMib;

        StagedCsv(byte[] bytes, int heldMib) {
            this.bytes = bytes;
            this.heldMib = heldMib;
        }

        byte[] bytes() {
            return bytes;
        }

        @Override
        public void close() {
            if (heldMib > 0) {
                S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.release(heldMib);
                heldMib = 0;
            }
        }
    }

    private final class StagingSession implements AutoCloseable {
        private final String accountId;
        private final RedshiftRoleAccess.RoleSession credentials;

        private StagingSession(String accountId, RedshiftRoleAccess.RoleSession credentials) {
            this.accountId = accountId;
            this.credentials = credentials;
        }

        List<Map<String, Object>> query(String sql, String setup) {
            if (credentials == null) {
                return duck.query(sql, setup, accountId);
            }
            return duck.query(sql, setup, credentials.accessKeyId(),
                    credentials.secretAccessKey(), credentials.sessionToken());
        }

        @Override
        public void close() {
            if (credentials != null) {
                iam.clearScratchResource(credentials.accessKeyId());
                iam.unregisterSession(accountId, credentials.accessKeyId());
            }
        }
    }

    private StagingSession openStagingSession(String accountId, String root, boolean authenticated) {
        if (!authenticated) {
            return new StagingSession(accountId, null);
        }
        String stagingAccountId = accountId != null && !accountId.isBlank() ? accountId : defaultAccountId;
        String partition = AwsRegions.partitionFor(region);
        RedshiftRoleAccess.RoleSession staging = RedshiftRoleAccess.mintStagingSession(iam, stagingAccountId, partition);
        try {
            iam.registerScratchResource(staging.accessKeyId(), AwsArnUtils.Arn.global(
                    partition, "s3", "", STAGING_BUCKET + "/" + root).toString());
            return new StagingSession(stagingAccountId, staging);
        } catch (RuntimeException exception) {
            iam.clearScratchResource(staging.accessKeyId());
            iam.unregisterSession(stagingAccountId, staging.accessKeyId());
            throw exception;
        }
    }

    record Column(String name, String type) { }

    record Description(List<Column> columns, PostgresWireDecoder.FrontendMessage error) { }

    /** Named Parse/Describe/Close leaves the client's portals and transaction intact. */
    static Description describe(Socket client, Socket backend, String query) throws IOException {
        String name = "floci_unload_" + UUID.randomUUID().toString().replace("-", "");
        backend.getOutputStream().write(PostgresWireDecoder.encodeParse(
                new PostgresWireDecoder.ParseMessage(name, query, List.of()), query));
        backend.getOutputStream().write(PostgresWire.frame('D', PostgresWire.cStringBytes("S" + name)));
        backend.getOutputStream().write(PostgresWire.frame('C', PostgresWire.cStringBytes("S" + name)));
        backend.getOutputStream().write(PostgresWire.frame('H', new byte[0]));
        backend.getOutputStream().flush();
        PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
        byte[] rowDescription = null;
        S3CopySimulator.S3TransferException rejectedType = null;
        PostgresWireDecoder.FrontendMessage message;
        while ((message = decoder.nextMessage()) != null) {
            if (message.type() == 'N' || message.type() == 'A' || message.type() == 'S') {
                client.getOutputStream().write(message.toPacketBytes());
                client.getOutputStream().flush();
            } else if (message.type() == 'T') {
                rowDescription = message.body();
            } else if (message.type() == 'E') {
                return new Description(List.of(), rejectedType == null ? message
                        : new PostgresWireDecoder.FrontendMessage('E',
                                S3CopySimulator.errorBody(rejectedType.sqlState(), rejectedType.getMessage())));
            } else if (message.type() == '3') {
                try {
                    return new Description(rowDescription == null ? List.of() : columns(rowDescription), null);
                } catch (S3CopySimulator.S3TransferException exception) {
                    rejectedType = exception;
                    // Fail the backend cycle so normal Sync recovery also handles local type errors.
                    String abortSql = "(FLOCI_ABORT_TX)";
                    backend.getOutputStream().write(PostgresWireDecoder.encodeParse(
                            new PostgresWireDecoder.ParseMessage(name, abortSql, List.of()), abortSql));
                    backend.getOutputStream().write(PostgresWire.frame('H', new byte[0]));
                    backend.getOutputStream().flush();
                }
            }
        }
        throw new IOException("Backend closed while describing UNLOAD query");
    }

    static List<Column> columns(byte[] description) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(description));
        int count = input.readUnsignedShort();
        List<Column> columns = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ByteArrayOutputStream name = new ByteArrayOutputStream();
            int character;
            while ((character = input.readUnsignedByte()) != 0) {
                name.write(character);
            }
            input.readInt(); // table OID
            input.readShort(); // attribute number
            int oid = input.readInt();
            input.readShort(); // type size
            int modifier = input.readInt();
            input.readShort(); // format code
            String columnName = name.toString(StandardCharsets.UTF_8);
            columns.add(new Column(columnName, type(columnName, oid, modifier)));
        }
        return columns;
    }

    static String type(String column, int oid, int modifier) {
        return switch (oid) {
            case 16 -> "BOOLEAN";
            case 21 -> "SMALLINT";
            case 23 -> "INTEGER";
            case 20 -> "BIGINT";
            case 700 -> "FLOAT";
            case 701 -> "DOUBLE";
            case 1082 -> "DATE";
            case 1114 -> "TIMESTAMP";
            case 1184 -> "TIMESTAMPTZ";
            case 1700 -> {
                if (modifier < 4) {
                    throw unsupported(column, "NUMERIC without declared precision and scale",
                            "Cast it to DECIMAL(p,s), with 1 <= p <= 38 and 0 <= s <= p.");
                }
                int precision = (modifier - 4) >> 16;
                int scale = ((modifier - 4) & 0x7ff) ^ 0x400;
                scale -= 0x400;
                if (precision < 1 || precision > 38 || scale < 0 || scale > precision) {
                    throw unsupported(column, "NUMERIC(" + precision + "," + scale + ")",
                            "Cast it to DECIMAL(p,s), with 1 <= p <= 38 and 0 <= s <= p.");
                }
                yield "DECIMAL(" + precision + "," + scale + ")";
            }
            case 25, 1042, 1043 -> "VARCHAR";
            default -> {
                String typeName = switch (oid) {
                    case 17 -> "BYTEA";
                    case 114 -> "JSON";
                    case 1083 -> "TIME";
                    case 1186 -> "INTERVAL";
                    case 1266 -> "TIMETZ";
                    case 2950 -> "UUID";
                    case 3802 -> "JSONB";
                    default -> "PostgreSQL type";
                };
                throw unsupported(column, typeName + " (OID " + oid + ")",
                        "Cast it explicitly to a supported scalar type, such as VARCHAR.");
            }
        };
    }

    private static S3CopySimulator.S3TransferException unsupported(String column, String type, String hint) {
        return new S3CopySimulator.S3TransferException("0A000",
                "UNLOAD PARQUET does not support column \"" + column.replace("\"", "\"\"")
                        + "\" of type " + type + ". " + hint, null);
    }

    @FunctionalInterface
    private interface StagingWork<T> {
        T run(String root, StagingSession staging) throws IOException;
    }

    private <T> T withStaging(String accountId, RedshiftRoleAccess.RoleSession session,
                              String operation, String errorMessage, StagingWork<T> work) throws IOException {
        try {
            return RequestScopes.callAsChecked(accountId, () -> {
                S3ScratchSpace.UNLOAD.ensureBucket(s3, region);
                String root = "unload/" + UUID.randomUUID() + "/";
                try (StagingSession staging = openStagingSession(accountId, root, session != null)) {
                    return work.run(root, staging);
                } finally {
                    try {
                        s3.deleteObjectsWithPrefix(STAGING_BUCKET, root, ignored -> {
                            // Internal staging cleanup does not use the external role.
                        });
                    } catch (RuntimeException exception) {
                        LOG.warnv(exception, "Could not remove {0} staging prefix {1}", operation, root);
                    }
                }
            });
        } catch (RuntimeException | IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException(errorMessage, exception);
        }
    }

    List<byte[]> convert(byte[] csv, List<Column> columns, String accountId,
                         RedshiftRoleAccess.RoleSession session, long maxFileSizeBytes) throws IOException {
        if (columns.isEmpty()) {
            throw new IOException("UNLOAD query has no Parquet columns");
        }
        return withStaging(accountId, session, "UNLOAD", "Unable to convert UNLOAD slice", (root, staging) -> {
            String inputKey = root + "input.csv";
            String outputKey = root + "output.parquet";
            s3.putObject(STAGING_BUCKET, inputKey, csv, "text/csv", null);
            StringBuilder schema = new StringBuilder();
            StringBuilder projection = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    schema.append(", ");
                    projection.append(", ");
                }
                Column column = columns.get(i);
                schema.append("'c").append(i).append("': '").append(column.type()).append("'");
                String expression = "c" + i;
                if ("TIMESTAMPTZ".equals(column.type())) {
                    expression = "CAST(" + expression + " AS TIMESTAMP)";
                }
                projection.append(expression).append(" AS \"")
                        .append(column.name().replace("\"", "\"\"")).append("\"");
            }
            String source = csv.length == 0
                    ? emptyQuery(columns)
                    : "SELECT " + projection + " FROM read_csv('" + literal("s3://" + STAGING_BUCKET + "/" + inputKey)
                            + "', header = false, delim = ',', quote = '\"', escape = '\"', nullstr = '', "
                            + "allow_quoted_nulls = false, auto_detect = false, columns = {"
                            + schema + "})";
            String sql = "COPY (" + source + ") TO '"
                    + literal("s3://" + STAGING_BUCKET + "/" + outputKey)
                    + "' (FORMAT PARQUET, COMPRESSION SNAPPY, PARQUET_VERSION V1, FILE_SIZE_BYTES "
                    + maxFileSizeBytes + ", RETURN_STATS true)";
            String setup = "SET TimeZone = 'UTC'; SET threads = 1;";
            List<Map<String, Object>> files = staging.query(sql, setup);
            if (files.isEmpty()) {
                throw new IOException("DuckDB returned no Parquet output files");
            }
            List<byte[]> result = new ArrayList<>();
            for (Map<String, Object> file : files) {
                String key = file.get("filename").toString().substring(("s3://" + STAGING_BUCKET + "/").length());
                try (InputStream input = s3.openObjectStream(STAGING_BUCKET, key, null)) {
                    result.add(input.readAllBytes());
                }
            }
            return result;
        });
    }

    /**
     * Converts one already-authorized Parquet object into headerless UTF-8 CSV via {@code floci-duck},
     * bounded by {@link S3CopySimulator#COPY_TRANSFORM_MAX_OBJECT_BYTES} and sharing
     * {@link S3CopySimulator#COPY_TRANSFORM_HEAP_MIB} until the caller closes the returned {@link StagedCsv}.
     */
    StagedCsv readToCsv(byte[] parquetBytes, String accountId,
                        RedshiftRoleAccess.RoleSession session) throws IOException {
        if (parquetBytes == null || parquetBytes.length == 0) {
            return StagedCsv.EMPTY;
        }
        long maxBytes = S3CopySimulator.COPY_TRANSFORM_MAX_OBJECT_BYTES;
        if (parquetBytes.length > maxBytes) {
            throw new S3CopySimulator.S3TransferException("54000",
                    "COPY Parquet object exceeds the " + maxBytes + "-byte limit", null);
        }
        return withStaging(accountId, session, "COPY Parquet", "Unable to read Parquet COPY object",
                (root, staging) -> {
                    String inputKey = root + "input.parquet";
                    String outputKey = root + "output.csv";
                    int[] heldMib = new int[]{0};
                    boolean handedOff = false;
                    try {
                        // Stage the authorized bytes under a literal key so DuckDB never globs caller object names.
                        s3.putObject(STAGING_BUCKET, inputKey, parquetBytes, "application/octet-stream", null);
                        s3.registerScratchWriteLimit(STAGING_BUCKET, outputKey, maxBytes);
                        String sourceUri = "s3://" + STAGING_BUCKET + "/" + inputKey;
                        String sql = "COPY (SELECT * FROM read_parquet('" + literal(sourceUri)
                                + "', hive_partitioning = false)) TO '"
                                + literal("s3://" + STAGING_BUCKET + "/" + outputKey)
                                + "' (FORMAT CSV, HEADER false, DELIMITER ',', QUOTE '\"', ESCAPE '\"', NULL '')";
                        String setup = "SET TimeZone = 'UTC'; SET threads = 1;";
                        try {
                            staging.query(sql, setup);
                        } catch (RuntimeException exception) {
                            if (isEntityTooLarge(exception)) {
                                throw new S3CopySimulator.S3TransferException("54000",
                                        "COPY Parquet object exceeds the " + maxBytes + "-byte decompressed limit",
                                        exception);
                            }
                            throw exception;
                        }
                        if (!s3.objectExists(STAGING_BUCKET, outputKey)) {
                            return StagedCsv.EMPTY;
                        }
                        byte[] csv;
                        try (InputStream input = s3.openObjectStream(STAGING_BUCKET, outputKey, null)) {
                            csv = readBounded(input, maxBytes, heldMib);
                        }
                        StagedCsv staged = new StagedCsv(csv, heldMib[0]);
                        handedOff = true;
                        return staged;
                    } finally {
                        s3.clearScratchWriteLimit(STAGING_BUCKET, outputKey);
                        if (!handedOff && heldMib[0] > 0) {
                            S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.release(heldMib[0]);
                        }
                    }
                });
    }

    private static boolean isEntityTooLarge(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("EntityTooLarge")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static byte[] readBounded(InputStream input, long maxBytes, int[] heldMib) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new S3CopySimulator.S3TransferException("54000",
                        "COPY Parquet object exceeds the " + maxBytes + "-byte decompressed limit", null);
            }
            S3CopySimulator.acquireTransformHeap(heldMib, (int) ((total * 2L) / (1024 * 1024)) + 1);
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String emptyQuery(List<Column> columns) {
        List<String> expressions = new ArrayList<>();
        for (Column column : columns) {
            String type = "TIMESTAMPTZ".equals(column.type()) ? "TIMESTAMP" : column.type();
            expressions.add("CAST(NULL AS " + type + ") AS \""
                    + column.name().replace("\"", "\"\"") + "\"");
        }
        return "SELECT " + String.join(", ", expressions) + " WHERE false";
    }

    private static String literal(String value) {
        return value.replace("'", "''");
    }

}
