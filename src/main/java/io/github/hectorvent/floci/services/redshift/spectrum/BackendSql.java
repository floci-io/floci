package io.github.hectorvent.floci.services.redshift.spectrum;

import java.io.InputStream;

public interface BackendSql {
    void execute(String sql);
    long copyIn(String copySql, InputStream data);
}
