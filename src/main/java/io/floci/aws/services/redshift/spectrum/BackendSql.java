package io.floci.aws.services.redshift.spectrum;

import java.io.InputStream;

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

    void execute(String sql);
    long copyIn(String copySql, InputStream data);
}
