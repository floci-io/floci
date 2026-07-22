package io.github.hectorvent.floci.services.rdsdata;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
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
        return resolve(resourceArn, null);
    }

    DatabaseTarget resolve(String resourceArn, String requestRegion) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("BadRequestException", "resourceArn is required.", 400);
        }

        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
            if (!"rds".equals(arn.service())) {
                throw new IllegalArgumentException("not an RDS ARN");
            }
            if (requestRegion != null && !requestRegion.isBlank()
                    && !requestRegion.equals(arn.region())) {
                throw new IllegalArgumentException("RDS ARN is outside the request region");
            }
            int separator = arn.resource().indexOf(':');
            if (separator <= 0 || separator == arn.resource().length() - 1) {
                throw new IllegalArgumentException("invalid RDS resource");
            }
            String type = arn.resource().substring(0, separator);
            String id = arn.resource().substring(separator + 1);
            if ("cluster".equals(type)) {
                DbCluster cluster = rdsService.getDbCluster(id, arn.region());
                if (resourceArn.equals(cluster.getDbClusterArn())) {
                    return fromCluster(cluster);
                }
            } else if ("db".equals(type)) {
                DbInstance instance = rdsService.getDbInstance(id, arn.region());
                if (resourceArn.equals(instance.getDbInstanceArn())) {
                    return fromInstance(instance);
                }
            }
        } catch (AwsException | IllegalArgumentException ignored) {
            // Normalize lookup and ARN parsing failures to the RDS Data API error shape below.
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

    record DatabaseTarget(String arn, DatabaseEngine engine, String host, int port,
                          String username, String password, String databaseName) {
    }
}
