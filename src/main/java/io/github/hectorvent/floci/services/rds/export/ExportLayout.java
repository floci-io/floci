package io.github.hectorvent.floci.services.rds.export;

import java.util.UUID;

/**
 * Where an export task writes in its bucket. Each table's data goes under
 * {@code <S3Prefix>/<task>/<database>/<schema>.<table>/<batch>/}, named
 * {@code part-<partition>-<uuid>.gz.parquet}, and the task's own description sits at
 * {@code <S3Prefix>/<task>/}. Floci reads each table in one batch and one partition.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_ExportSnapshot.FileNames.html">
 *     File naming conventions for exports to Amazon S3</a>
 */
final class ExportLayout {

    private final String taskRoot;
    private final String taskId;

    ExportLayout(String s3Prefix, String taskId) {
        String prefix = s3Prefix == null ? "" : stripSlashes(s3Prefix);
        this.taskId = taskId;
        this.taskRoot = (prefix.isEmpty() ? "" : prefix + "/") + pathComponent(taskId) + "/";
    }

    String taskRoot() {
        return taskRoot;
    }

    String dataFileKey(String database, String schema, String table) {
        return taskRoot + pathComponent(database) + "/" + pathComponent(schema) + "." + pathComponent(table)
                + "/1/part-00000-" + UUID.randomUUID() + ".gz.parquet";
    }

    String exportInfoKey() {
        return taskRoot + "export_info_" + taskId + ".json";
    }

    String tablesInfoKey(int tableCount) {
        return taskRoot + "export_tables_info_" + taskId + "_from_1_to_" + tableCount + ".json";
    }

    /** RDS writes a backslash, backquote, double quote or space in an object path as an underscore. */
    static String pathComponent(String name) {
        return name.replaceAll("[\\\\`\" ]", "_");
    }

    private static String stripSlashes(String prefix) {
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
}
