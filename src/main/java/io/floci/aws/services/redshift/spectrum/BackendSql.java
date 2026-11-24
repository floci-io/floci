package io.floci.aws.services.redshift.spectrum;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

public interface BackendSql {
    default boolean permitsCachePublication() {
        return true;
    }

    /**
     * Whether this backend runs inside an Extended Query pipeline where {@code SAVEPOINT} is rejected outside an
     * explicit transaction block and collect passes must run inside a PL/pgSQL subtransaction.
     */
    default boolean usesSubtransactionCollect() {
        return false;
    }

    /**
     * Resolves the PostgreSQL parameter type OIDs for {@code sql}, using any client-supplied {@code declaredTypeOids}.
     */
    default List<Integer> resolveParameterTypeOids(String sql, List<Integer> declaredTypeOids, int parameterCount) {
        return declaredTypeOids == null ? List.of() : declaredTypeOids;
    }

    /**
     * Runs a query that returns a single text value, binding raw PostgreSQL wire parameters when provided.
     */
    default Optional<String> queryString(String sql, RawParameters parameters) {
        return queryString(sql);
    }

    /**
     * Raw PostgreSQL Bind parameter section and type OIDs, preserving binary-format values verbatim.
     */
    record RawParameters(List<Integer> typeOids, int count, byte[] bindSection) {
        public RawParameters {
            typeOids = typeOids == null ? List.of() : List.copyOf(typeOids);
            bindSection = bindSection == null ? new byte[0] : bindSection.clone();
        }

        @Override
        public byte[] bindSection() {
            return bindSection.clone();
        }
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
