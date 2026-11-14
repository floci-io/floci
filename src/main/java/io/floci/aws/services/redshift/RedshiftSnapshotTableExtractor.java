package io.floci.aws.services.redshift;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the SQL that restores one table of a snapshot into a live database under another name. A
 * snapshot is a plain {@code pg_dump} script, so the table's {@code CREATE TABLE} statement and its
 * {@code COPY} data block are lifted out of it and renamed. Only the table's definition and rows are
 * restored: constraints, defaults that reference a sequence and ownership are not carried over.
 */
public final class RedshiftSnapshotTableExtractor {

    private static final String CREATE_TABLE = "CREATE TABLE ";
    private static final String COPY = "COPY ";
    private static final String COPY_END = "\\.";

    private RedshiftSnapshotTableExtractor() {
    }

    /**
     * The script that creates {@code targetSchema.targetTable} from {@code sourceSchema.sourceTable} of the
     * dump, or empty when the dump has no such table. Names match ignoring case unless {@code caseSensitive}.
     */
    public static Optional<String> restoreScript(Path dump, String sourceSchema, String sourceTable,
                                                 String targetSchema, String targetTable,
                                                 boolean caseSensitive) throws IOException {
        List<String> create = new ArrayList<>();
        List<String> data = new ArrayList<>();
        boolean inCreate = false;
        boolean inCopy = false;
        boolean copyMatches = false;
        try (BufferedReader reader = Files.newBufferedReader(dump, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (inCopy) {
                    if (copyMatches) {
                        data.add(line);
                    }
                    if (line.equals(COPY_END)) {
                        inCopy = false;
                        copyMatches = false;
                    }
                    continue;
                }
                if (inCreate) {
                    create.add(line);
                    if (line.endsWith(";")) {
                        inCreate = false;
                    }
                    continue;
                }
                if (line.startsWith(CREATE_TABLE) && create.isEmpty()) {
                    QualifiedName name = QualifiedName.parse(line, CREATE_TABLE.length());
                    if (name != null && name.matches(sourceSchema, sourceTable, caseSensitive)) {
                        create.add(CREATE_TABLE + quote(targetSchema) + "." + quote(targetTable)
                                + line.substring(name.end()));
                        inCreate = !line.endsWith(";");
                    }
                } else if (line.startsWith(COPY) && line.endsWith(" FROM stdin;")) {
                    inCopy = true;
                    QualifiedName name = QualifiedName.parse(line, COPY.length());
                    if (name != null && name.matches(sourceSchema, sourceTable, caseSensitive) && data.isEmpty()) {
                        copyMatches = true;
                        data.add(COPY + quote(targetSchema) + "." + quote(targetTable) + line.substring(name.end()));
                    }
                }
            }
        }
        if (create.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder script = new StringBuilder("BEGIN;\n");
        create.forEach(statement -> script.append(statement).append('\n'));
        data.forEach(row -> script.append(row).append('\n'));
        return Optional.of(script.append("COMMIT;\n").toString());
    }

    /** The identifier as a quoted one, so a name with spaces, quotes or capitals reaches Postgres unchanged. */
    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /** A {@code schema.table} name as pg_dump writes it, quoted when it needs to be, and where it ends. */
    private record QualifiedName(String schema, String table, int end) {

        static QualifiedName parse(String line, int from) {
            Part schema = part(line, from);
            if (schema == null || schema.end() >= line.length() || line.charAt(schema.end()) != '.') {
                return null;
            }
            Part table = part(line, schema.end() + 1);
            return table == null ? null : new QualifiedName(schema.value(), table.value(), table.end());
        }

        boolean matches(String sourceSchema, String sourceTable, boolean caseSensitive) {
            return caseSensitive
                    ? schema.equals(sourceSchema) && table.equals(sourceTable)
                    : schema.equalsIgnoreCase(sourceSchema) && table.equalsIgnoreCase(sourceTable);
        }

        private record Part(String value, int end) {
        }

        private static Part part(String line, int from) {
            if (from >= line.length()) {
                return null;
            }
            StringBuilder value = new StringBuilder();
            int index = from;
            if (line.charAt(index) == '"') {
                index++;
                while (index < line.length()) {
                    char c = line.charAt(index);
                    if (c == '"') {
                        if (index + 1 < line.length() && line.charAt(index + 1) == '"') {
                            value.append('"');
                            index += 2;
                            continue;
                        }
                        return new Part(value.toString(), index + 1);
                    }
                    value.append(c);
                    index++;
                }
                return null;
            }
            while (index < line.length() && (Character.isLetterOrDigit(line.charAt(index))
                    || line.charAt(index) == '_' || line.charAt(index) == '$')) {
                value.append(line.charAt(index));
                index++;
            }
            return value.isEmpty() ? null : new Part(value.toString(), index);
        }
    }
}
