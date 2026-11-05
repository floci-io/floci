package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.PostgresWire;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3ScratchSpace;
import io.github.hectorvent.floci.services.s3.S3Service;
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

    public RedshiftParquetWriter(FlociDuckClient duck, S3Service s3, IamService iam, EmulatorConfig config) {
        this.duck = duck;
        this.s3 = s3;
        this.iam = iam;
        region = config.defaultRegion();
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

    List<byte[]> convert(byte[] csv, List<Column> columns, String accountId,
                         RedshiftRoleAccess.RoleSession session, long maxFileSizeBytes) throws IOException {
        try {
            return RequestScopes.callAsChecked(accountId,
                    () -> convertScoped(csv, columns, accountId, session, maxFileSizeBytes));
        } catch (RuntimeException | IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Unable to convert UNLOAD slice", exception);
        }
    }

    private List<byte[]> convertScoped(byte[] csv, List<Column> columns, String accountId,
                                       RedshiftRoleAccess.RoleSession session, long maxFileSizeBytes) throws IOException {
        if (columns.isEmpty()) {
            throw new IOException("UNLOAD query has no Parquet columns");
        }
        S3ScratchSpace.UNLOAD.ensureBucket(s3, region);
        String root = "unload/" + UUID.randomUUID() + "/";
        String inputKey = root + "input.csv";
        String outputKey = root + "output.parquet";
        try {
            if (session != null) {
                iam.registerScratchResource(session.accessKeyId(), AwsArnUtils.Arn.global(
                        AwsRegions.partitionFor(region), "s3", "", STAGING_BUCKET + "/" + root).toString());
            }
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
            List<Map<String, Object>> files = session == null
                    ? duck.query(sql, setup, accountId)
                    : duck.query(sql, setup, session.accessKeyId(), session.secretAccessKey(), session.sessionToken());
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
        } finally {
            if (session != null) {
                iam.clearScratchResource(session.accessKeyId());
            }
            try {
                s3.deleteObjectsWithPrefix(STAGING_BUCKET, root, ignored -> {
                    // Internal staging cleanup does not use the external role.
                });
            } catch (RuntimeException exception) {
                LOG.warnv(exception, "Could not remove UNLOAD staging prefix {0}", root);
            }
        }
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
