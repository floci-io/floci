package io.github.hectorvent.floci.services.rds.export;

import java.time.Instant;

/**
 * What an export task reads from a DB snapshot or DB cluster snapshot: the image its data
 * restores into, the master login, the time it was taken and its dump.
 */
public record SnapshotExportSource(String sourceArn, String image, String masterUsername,
                                   String masterPassword, Instant snapshotTime, String sqlDump) {}
