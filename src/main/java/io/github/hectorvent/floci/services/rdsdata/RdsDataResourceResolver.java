package io.github.hectorvent.floci.services.rdsdata;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
class RdsDataResourceResolver {

    private final RdsService rdsService;

    @Inject
    RdsDataResourceResolver(RdsService rdsService) {
        this.rdsService = rdsService;
    }

    DatabaseTarget resolve(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("BadRequestException", "resourceArn is required.", 400);
        }

        for (DbCluster cluster : rdsService.listDbClusters(null)) {
            if (resourceArn.equals(cluster.getDbClusterArn())) {
                return fromCluster(cluster);
            }
        }
        for (DbInstance instance : rdsService.listDbInstances(null)) {
            if (resourceArn.equals(instance.getDbInstanceArn())) {
                return fromInstance(instance);
            }
        }

        String id = identifierFromArn(resourceArn);
        if (id != null) {
            try {
                return fromCluster(rdsService.getDbCluster(id));
            } catch (AwsException ignored) {
                // Try instances below.
            }
            try {
                return fromInstance(rdsService.getDbInstance(id));
            } catch (AwsException ignored) {
                // Fall through to the Data API error shape.
            }
        }

        throw new AwsException("BadRequestException",
                "resourceArn does not resolve to a local RDS resource: " + resourceArn, 400);
    }

    private static DatabaseTarget fromCluster(DbCluster cluster) {
        return target(cluster.getDbClusterArn(), cluster.getEngine(), cluster.getContainerHost(), cluster.getContainerPort(),
                cluster.getMasterUsername(), cluster.getMasterPassword(), cluster.getDatabaseName());
    }

    private static DatabaseTarget fromInstance(DbInstance instance) {
        return target(instance.getDbInstanceArn(), instance.getEngine(), instance.getContainerHost(), instance.getContainerPort(),
                instance.getMasterUsername(), instance.getMasterPassword(), instance.getDbName());
    }

    private static DatabaseTarget target(String arn, DatabaseEngine engine, String host, int port,
                                         String username, String password, String databaseName) {
        if (host == null || host.isBlank() || port <= 0) {
            throw new AwsException("BadRequestException",
                    "RDS resource runtime is not available for Data API execution.", 400);
        }
        return new DatabaseTarget(arn, engine, host, port, username, password, databaseName);
    }

    private static String identifierFromArn(String arn) {
        int marker = arn.lastIndexOf(":cluster:");
        if (marker >= 0) {
            return arn.substring(marker + ":cluster:".length());
        }
        marker = arn.lastIndexOf(":db:");
        if (marker >= 0) {
            return arn.substring(marker + ":db:".length());
        }
        return null;
    }

    record DatabaseTarget(String arn, DatabaseEngine engine, String host, int port,
                          String username, String password, String databaseName) {
    }
}
