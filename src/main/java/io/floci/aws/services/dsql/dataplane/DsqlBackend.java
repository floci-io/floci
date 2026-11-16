package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.services.dsql.model.DsqlCluster;

import java.io.IOException;
import java.sql.SQLException;

/** Where a cluster's database lives, and how it is brought up and taken down. */
public interface DsqlBackend {

    /** Starts the cluster's database, waits until it answers, and sets it up as DSQL's database is. */
    DsqlDatabases.Backend start(DsqlCluster cluster) throws SQLException, IOException;

    /** Stops the cluster's database and, with {@code dropData}, removes its data. */
    void stop(DsqlCluster cluster, boolean dropData);
}
