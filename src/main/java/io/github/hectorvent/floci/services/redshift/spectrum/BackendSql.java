package io.github.hectorvent.floci.services.redshift.spectrum;

import java.io.InputStream;

public interface BackendSql {
    default boolean permitsCachePublication() {
        return true;
    }

    void execute(String sql);
    long copyIn(String copySql, InputStream data);
}
