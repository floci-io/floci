package io.github.hectorvent.floci.services.redshift.proxy;

/**
 * Functional callback to record COPY operation errors into {@code pg_catalog.stl_load_errors}.
 */
@FunctionalInterface
public interface LoadErrorRecorder {

    LoadErrorRecorder NOOP = (filename, lineNumber, colname, errCode, errReason) -> {};

    void record(String filename, long lineNumber, String colname, int errCode, String errReason);
}
