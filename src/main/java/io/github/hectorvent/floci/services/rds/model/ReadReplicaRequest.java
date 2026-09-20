package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * The CreateDBInstanceReadReplica request as the service consumes it. Every field but the two
 * identifiers is optional; a null means "inherit from the source", which is what AWS does for the
 * instance class, storage, parameter and option groups, subnet group and minor version upgrades.
 */
@RegisterForReflection
public record ReadReplicaRequest(String dbInstanceIdentifier,
                                 String sourceDbInstanceIdentifier,
                                 String dbInstanceClass,
                                 String availabilityZone,
                                 Boolean multiAz,
                                 Boolean autoMinorVersionUpgrade,
                                 String optionGroupName,
                                 String dbParameterGroupName,
                                 Boolean publiclyAccessible,
                                 String dbSubnetGroupName,
                                 List<String> vpcSecurityGroupIds,
                                 Boolean copyTagsToSnapshot,
                                 Boolean iamDatabaseAuthenticationEnabled,
                                 Integer allocatedStorage,
                                 String replicaMode,
                                 Map<String, String> tags) {
}
