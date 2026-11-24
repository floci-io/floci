package io.floci.aws.services.redshift.spectrum;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

public interface BackendSql {
    default boolean permitsCachePublication() {
        return true;
    }

    /**
     * Whether commands on this backend already run inside an implicit transaction block (the PostgreSQL Extended
     * Query cycle), so rollback scopes must use {@code SAVEPOINT} rather than {@code BEGIN}/{@code ROLLBACK}.
     */
    default boolean inImplicitTransaction() {
        return false;
    }

    /**
     * Identifies the client connection whose transaction this backend runs in, so state staged for that
     * transaction can be published or dropped when it ends. Null when the end of the transaction is not reported.
     */
    default Object connectionKey() {
        return null;
    }

    /**
     * Runs a query that yields at most one text value: the first column of the first row, or empty when there is no
     * row or the value is SQL NULL.
     */
    default Optional<String> queryString(String sql) {
        return Optional.empty();
    }

    /**
     * Runs a query and returns every row as text values in column order, with {@code null} for SQL NULL.
     */
    default List<List<String>> queryRows(String sql) {
        return List.of();
    }

    void execute(String sql);

    /**
     * Runs a statement with positional text parameters ({@code null} for SQL NULL), or plainly when empty.
     */
    default void execute(String sql, List<String> parameters) {
        execute(sql);
    }

    long copyIn(String copySql, InputStream data);
}
