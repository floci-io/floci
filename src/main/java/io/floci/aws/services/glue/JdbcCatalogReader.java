package io.floci.aws.services.glue;

import java.sql.SQLException;
import java.util.List;

/** Reads the tables and views, with their columns, that a JDBC session to a data store can select from. */
@FunctionalInterface
interface JdbcCatalogReader {

    /**
     * @throws SQLException when the session cannot open, as for an unreachable endpoint or a refused sign-in,
     *                      or when the catalog query fails
     */
    List<JdbcTableSchema> read(String jdbcUrl, String user, String password) throws SQLException;

    /** A table or view; {@code comment} is its database comment, or null. */
    record JdbcTableSchema(String schema, String name, boolean view, String comment, List<JdbcColumn> columns) {
    }

    /** A column as the database declares it: {@code rawType} is the native type, such as {@code numeric(10,2)}. */
    record JdbcColumn(String name, String rawType, String comment) {
    }
}
