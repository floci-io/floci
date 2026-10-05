package io.github.hectorvent.floci.services.redshift.spectrum;

import java.io.InputStream;

public interface BackendSql {
    default boolean permitsCachePublication() {
        return true;
    }

    /** Identity of the transaction this backend runs in, so work staged by one statement can be undone by a later one. */
    default Object transactionScope() {
        return this;
    }

    void execute(String sql);
    long copyIn(String copySql, InputStream data);
}
