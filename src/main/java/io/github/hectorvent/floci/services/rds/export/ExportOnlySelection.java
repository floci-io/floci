package io.github.hectorvent.floci.services.rds.export;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The {@code ExportOnly} list of a PostgreSQL export: each entry names a {@code database}, a
 * {@code database.schema} or a {@code database.schema.table}, and an empty list exports
 * everything. RDS supports a partial export only for names made of letters, digits, {@code $} and
 * {@code _}, so any other entry is refused.
 */
final class ExportOnlySelection {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9$_]+");

    private final List<String[]> entries;

    private ExportOnlySelection(List<String[]> entries) {
        this.entries = entries;
    }

    static ExportOnlySelection of(List<String> exportOnly) {
        if (exportOnly == null || exportOnly.isEmpty()) {
            return new ExportOnlySelection(List.of());
        }
        List<String[]> parsed = exportOnly.stream().map(ExportOnlySelection::parse).toList();
        return new ExportOnlySelection(parsed);
    }

    private static String[] parse(String entry) {
        String[] parts = entry.split("\\.", -1);
        if (parts.length > 3) {
            throw invalid(entry);
        }
        for (String part : parts) {
            if (!IDENTIFIER.matcher(part).matches()) {
                throw invalid(entry);
            }
        }
        return parts;
    }

    private static AwsException invalid(String entry) {
        return new AwsException("InvalidExportOnly",
                "The ExportOnly value " + entry + " isn't valid for a PostgreSQL export: use database, "
                        + "database.schema or database.schema.table.", 400);
    }

    boolean includesDatabase(String database) {
        return entries.isEmpty() || entries.stream().anyMatch(entry -> entry[0].equals(database));
    }

    boolean includes(String database, String schema, String table) {
        if (entries.isEmpty()) {
            return true;
        }
        for (String[] entry : entries) {
            if (!entry[0].equals(database)) {
                continue;
            }
            if (entry.length == 1
                    || (entry.length == 2 && entry[1].equals(schema))
                    || (entry.length == 3 && entry[1].equals(schema) && entry[2].equals(table))) {
                return true;
            }
        }
        return false;
    }
}
