package io.floci.aws.services.redshift.proxy;

import io.floci.aws.services.redshift.proxy.S3CopySimulator.ColumnInfo;
import io.floci.aws.services.redshift.proxy.S3CopySimulator.S3TransferException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A load diagnostic is independent of the transaction that attempted the load. */
record CopyLoadError(Context context, String filename, long lineNumber, String column, String type,
                     String columnLength, String rawLine, String rawFieldValue, int code, String reason) {
    private static final AtomicInteger QUERY_IDS = new AtomicInteger();

    record Context(int userId, int sessionId, long tableId, int queryId, Instant started) {
        static Context create(List<List<String>> rows) {
            List<String> values = rows.isEmpty() ? List.of("0", "0", "0") : rows.getFirst();
            return new Context(Integer.parseInt(values.get(0)), Integer.parseInt(values.get(1)),
                    Long.parseLong(values.get(2)), QUERY_IDS.incrementAndGet(), Instant.now());
        }
    }

    static CopyLoadError from(Context context, CopyStatementParser.S3CopyFrom spec, String key, long line,
                              byte[] record, List<ColumnInfo> columns, S3TransferException error) {
        String column = error.fields().getOrDefault('c', "");
        boolean backendError = error.fields().containsKey('M');
        String rawField = backendError ? "" : error.fields().getOrDefault('d', "");
        String detail = error.fields().getOrDefault('W', "");
        int marker = detail.lastIndexOf(", column ");
        if (column.isEmpty() && marker >= 0) {
            int end = detail.indexOf(": ", marker + 9);
            if (end >= 0) {
                column = detail.substring(marker + 9, end);
                rawField = detail.substring(end + 2);
                if (rawField.startsWith("\"") && rawField.endsWith("\"")) {
                    rawField = rawField.substring(1, rawField.length() - 1);
                }
            }
        }
        List<String> names = spec.columns().isEmpty() ? columns.stream().map(ColumnInfo::name).toList()
                : spec.columns().stream().map(S3CopySimulator::normalizeColumnName).toList();
        if (!backendError && column.matches("[0-9]+")) {
            int index = Integer.parseInt(column) - 1;
            column = index >= 0 && index < names.size() ? names.get(index) : "";
        }
        String name = column;
        ColumnInfo info = columns.stream().filter(value -> value.name().equals(name)).findFirst().orElse(null);
        String type = info == null ? "" : typeName(info.typeOid());
        String length = info != null && info.maxBytes() != null ? info.maxBytes().toString() : "";
        int contentEnd = record.length;
        while (contentEnd > 0 && (record[contentEnd - 1] == '\r' || record[contentEnd - 1] == '\n')) {
            contentEnd--;
        }
        String raw = new String(record, 0, Math.min(contentEnd, 1024), StandardCharsets.UTF_8);
        if (spec.jsonAuto()) {
            boolean afterCarriageReturn = false;
            for (int index = 0; index < contentEnd; index++) {
                byte value = record[index];
                if (value == '\r' || (value == '\n' && !afterCarriageReturn)) {
                    line++;
                }
                afterCarriageReturn = value == '\r';
            }
        }
        return new CopyLoadError(context, limit("s3://" + spec.bucket() + "/" + key, 256), line,
                limit(column, 127), type, length, ascii(raw, 1024), ascii(rawField, 1024),
                errorCode(error.sqlState(), type, raw, error.getMessage()), limit(error.getMessage(), 100));
    }

    private static int errorCode(String state, String type, String raw, String message) {
        return switch (state) {
            case "22003" -> 1204;
            case "22007", "22008" -> type.equals("timestamp") ? 1206 : 1205;
            case "22001" -> 1204;
            case "22021" -> 1220;
            case "23502" -> 1213;
            case "22P02" -> switch (type) {
                case "numeric" -> 1209;
                case "float4", "float8" -> 1208;
                case "bool" -> 1210;
                default -> 1207;
            };
            default -> raw.isEmpty() ? 1211 : message.contains("missing data for column") ? 1203
                    : message.contains("extra data after last expected column") ? 1202 : 1200;
        };
    }

    private static String typeName(String oid) {
        return switch (oid == null ? "" : oid) {
            case "16" -> "bool";
            case "20" -> "int8";
            case "21" -> "int2";
            case "23" -> "int4";
            case "700" -> "float4";
            case "701" -> "float8";
            case "1700" -> "numeric";
            case "1082" -> "date";
            case "1114", "1184" -> "timestamp";
            case "1042" -> "bpchar";
            case "1043" -> "varchar";
            case "25" -> "text";
            default -> "";
        };
    }

    static String limit(String value, int size) {
        String cleaned = value == null ? "" : value.replace('\0', '.');
        return cleaned.codePointCount(0, cleaned.length()) <= size ? cleaned
                : cleaned.substring(0, cleaned.offsetByCodePoints(0, size));
    }

    private static String ascii(String value, int size) {
        return limit(value, size).replaceAll("[^\\x00-\\x7f]", ".");
    }
}
