package io.floci.aws.services.cloudformation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The committed {@code drop-policy.tsv}: what an update does with a property the previous template
 * declared and the new one drops, one row per writable property that a provisioner arm calling
 * {@code ProvisionContext.droppedProperties} reads. Shared by {@link CfnSchemaCoverageTest}, which
 * checks the rows against the sources, and by the AppSync provisioner test, which checks them
 * against {@code AppSyncCfnProvisioner.dropPolicy}.
 *
 * <p>Columns, tab separated, no header: type, property, policy, and an optional reason. A policy is
 * {@code clear}, {@code default:<value>}, {@code keep:<reason>} or {@code create-only}. {@code create-only}
 * marks a create-only property: a drop is skipped or refused, never cleared.
 */
public final class CfnDropPolicyTable {

    public static final Path FILE = Path.of("src/test/resources/cloudformation/drop-policy.tsv");

    /** One row: the policy as written, and the optional fourth column. */
    public record Row(String policy, String reason) {

        /** {@code clear}, {@code default}, {@code keep} or {@code create-only}. */
        public String kind() {
            int colon = policy.indexOf(':');
            return colon < 0 ? policy : policy.substring(0, colon);
        }

        /** The text after the colon of {@code default:} and {@code keep:}, empty otherwise. */
        public String detail() {
            int colon = policy.indexOf(':');
            return colon < 0 ? "" : policy.substring(colon + 1);
        }
    }

    private CfnDropPolicyTable() {
    }

    /** "type&lt;TAB&gt;property" to its row. */
    public static Map<String, Row> load() {
        try {
            return parse(Files.readAllLines(FILE, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + FILE, e);
        }
    }

    /** A repeated key, a wrong field count or an unknown policy is refused with the line named. */
    public static Map<String, Row> parse(List<String> lines) {
        Map<String, Row> rows = new TreeMap<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length < 3 || parts.length > 4) {
                throw new AssertionError("Expected 'type<TAB>property<TAB>policy[<TAB>reason]' in " + FILE
                        + " but found: " + line);
            }
            Row row = new Row(parts[2], parts.length == 4 ? parts[3] : "");
            if (!List.of("clear", "default", "keep", "create-only").contains(row.kind())) {
                throw new AssertionError("Unknown policy '" + parts[2] + "' in " + FILE + ": " + line
                        + ". Use clear, default:<value>, keep:<reason> or create-only.");
            }
            String key = parts[0] + "\t" + parts[1];
            if (rows.put(key, row) != null) {
                throw new AssertionError("Two rows for " + parts[0] + " " + parts[1] + " in " + FILE
                        + "; the later would replace the earlier.");
            }
        }
        return rows;
    }
}
