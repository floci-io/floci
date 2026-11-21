package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.services.dsql.model.DsqlCluster;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Where a cluster's database lives, and how it is brought up and taken down. */
public interface DsqlBackend {

    /** Starts the cluster's database, waits until it answers, and sets it up as DSQL's database is. */
    DsqlDatabases.Backend start(DsqlCluster cluster) throws SQLException, IOException;

    /** The backend roles that belong to the cluster alone and go with it. */
    List<String> rolesToDrop(DsqlCluster cluster) throws SQLException;

    /** Stops the cluster's database and, with {@code dropData}, removes its data. */
    void stop(DsqlCluster cluster, boolean dropData);

    /**
     * What may stay behind when a FAILED cluster's deletion cannot clean up, if the backend lets
     * such a deletion finish anyway; empty when the deletion waits and is retried instead.
     */
    default Optional<String> leftoversOfFailed(DsqlCluster cluster) {
        return Optional.empty();
    }
}
