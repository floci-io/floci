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

        DbCluster cluster = null;
        DbInstance instance = null;
        String region = null;
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
            region = arn.region();
            if ("cluster".equals(type)) {
                DbCluster found = rdsService.getDbCluster(id, region);
                if (resourceArn.equals(found.getDbClusterArn())) {
                    cluster = found;
                }
            } else if ("db".equals(type)) {
                DbInstance found = rdsService.getDbInstance(id, region);
                if (resourceArn.equals(found.getDbInstanceArn())) {
                    instance = found;
                }
            }
        } catch (AwsException | IllegalArgumentException ignored) {
            // Normalize lookup and ARN parsing failures to the RDS Data API error shape below.
        }

        if (cluster != null) {
            return fromCluster(cluster, region);
        }
        if (instance != null) {
            return fromInstance(instance, region);
        }
        throw new AwsException("BadRequestException",
                "resourceArn does not resolve to a local RDS resource: " + resourceArn, 400);
    }

    private DatabaseTarget fromCluster(DbCluster cluster, String region) {
        DbCluster resolved = hasRuntime(cluster.getContainerHost(), cluster.getContainerPort())
                ? cluster
                : rdsService.ensureClusterBackend(cluster.getDbClusterIdentifier(), region);
        return target(resolved.getDbClusterArn(), resolved.getEngine(), resolved.getContainerHost(),
                resolved.getContainerPort(), resolved.getMasterUsername(), resolved.getMasterPassword(),
                resolved.getDatabaseName());
    }

    private DatabaseTarget fromInstance(DbInstance instance, String region) {
        DbInstance resolved = hasRuntime(instance.getContainerHost(), instance.getContainerPort())
                ? instance
                : rdsService.ensureInstanceBackend(instance.getDbInstanceIdentifier(), region);
        return target(resolved.getDbInstanceArn(), resolved.getEngine(), resolved.getContainerHost(),
                resolved.getContainerPort(), resolved.getMasterUsername(), resolved.getMasterPassword(),
                resolved.getDbName());
    }

    private static boolean hasRuntime(String host, int port) {
        return host != null && !host.isBlank() && port > 0;
    }

    private DatabaseTarget target(String arn, DatabaseEngine engine, String host, int port,
                                  String username, String password, String databaseName) {
        if (!hasRuntime(host, port)) {
            // The Data API is RDS's data plane: it needs a real database, which Floci can only
            // provide through a Docker container. Name the missing daemon rather than reporting a
            // generic runtime failure, in the Data API's modelled server-side error shape.
            if (!rdsService.isBackendRuntimeAvailable()) {
                throw new AwsException("InternalServerErrorException",
                        "The RDS backing database is unavailable because no Docker daemon is reachable "
                                + "from Floci. DB instance and cluster metadata operations are supported; "
                                + "Data API execution requires Docker.", 500);
            }
            throw new AwsException("BadRequestException",
                    "RDS resource runtime is not available for Data API execution.", 400);
        }
        return new DatabaseTarget(arn, engine, host, port, username, password, databaseName);
    }

    record DatabaseTarget(String arn, DatabaseEngine engine, String host, int port,
                          String username, String password, String databaseName) {
    }
}
