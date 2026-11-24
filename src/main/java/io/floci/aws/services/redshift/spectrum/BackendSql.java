package io.floci.aws.services.redshift.spectrum;

import java.io.InputStream;
import java.util.Optional;

public interface BackendSql {
    default boolean permitsCachePublication() {
        return true;
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

    void execute(String sql);
    long copyIn(String copySql, InputStream data);
}
