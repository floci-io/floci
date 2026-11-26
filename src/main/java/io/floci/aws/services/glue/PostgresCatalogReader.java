package io.floci.aws.services.glue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads the tables and views of a PostgreSQL wire database, the protocol a Floci Redshift cluster or
 * workgroup speaks. Only relations the signed-in user can select from are returned, and system and Floci
 * schemas are left out.
 */
final class PostgresCatalogReader implements JdbcCatalogReader {

    // An endpoint that never answers must not hold a crawl forever.
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int SOCKET_TIMEOUT_SECONDS = 60;

    static final String CATALOG_SQL = """
            SELECT n.nspname, c.relname, c.relkind IN ('v', 'm') AS is_view,
                   obj_description(c.oid, 'pg_class') AS table_comment,
                   a.attname, format_type(a.atttypid, a.atttypmod) AS column_type,
                   col_description(c.oid, a.attnum) AS column_comment
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
            WHERE c.relkind IN ('r', 'p', 'v', 'm')
              AND NOT c.relispartition
              AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast', 'floci_internal', 'sys')
              AND n.nspname NOT LIKE 'pg\\_%'
              AND has_table_privilege(c.oid, 'SELECT')
            ORDER BY n.nspname, c.relname, a.attnum
            """;

    @Override
    public List<JdbcTableSchema> read(String jdbcUrl, String user, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.setProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT_SECONDS));
        try (Connection connection = DriverManager.getConnection(jdbcUrl, properties);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(CATALOG_SQL)) {
            List<CatalogRow> catalog = new ArrayList<>();
            while (rows.next()) {
                catalog.add(new CatalogRow(rows.getString(1), rows.getString(2), rows.getBoolean(3),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7)));
            }
            return group(catalog);
        }
    }

    /** One column of one relation, as a row of {@link #CATALOG_SQL}. */
    record CatalogRow(String schema, String table, boolean view, String tableComment, String column,
                      String columnType, String columnComment) {
    }

    /** A relation's identity. Schema and name stay apart: quoted names may hold dots. */
    private record RelationKey(String schema, String table) {
    }

    /** Gathers the columns of each relation, keeping the order of the rows. */
    static List<JdbcTableSchema> group(List<CatalogRow> rows) {
        Map<RelationKey, List<CatalogRow>> byRelation = new LinkedHashMap<>();
        for (CatalogRow row : rows) {
            byRelation.computeIfAbsent(new RelationKey(row.schema(), row.table()), key -> new ArrayList<>()).add(row);
        }
        List<JdbcTableSchema> tables = new ArrayList<>();
        for (List<CatalogRow> relation : byRelation.values()) {
            CatalogRow first = relation.getFirst();
            List<JdbcColumn> columns = new ArrayList<>();
            for (CatalogRow row : relation) {
                columns.add(new JdbcColumn(row.column(), row.columnType(), row.columnComment()));
            }
            tables.add(new JdbcTableSchema(first.schema(), first.table(), first.view(), first.tableComment(),
                    List.copyOf(columns)));
        }
        return tables;
    }
}
