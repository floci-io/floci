package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The RestoreDBInstanceToPointInTime request as the service consumes it. The source is named by
 * identifier, DbiResourceId or automated backups ARN; the time by RestoreTime or
 * UseLatestRestorableTime. A null override means the API reference's default: the source's
 * instance class, and the Region's defaults for everything else.
 */
@RegisterForReflection
public record PointInTimeRestoreRequest(String targetDbInstanceIdentifier,
                                        String sourceDbInstanceIdentifier,
                                        String sourceDbiResourceId,
                                        String sourceDbInstanceAutomatedBackupsArn,
                                        Instant restoreTime,
                                        Boolean useLatestRestorableTime,
                                        String dbInstanceClass,
                                        Integer port,
                                        String availabilityZone,
                                        Boolean multiAz,
                                        Boolean autoMinorVersionUpgrade,
                                        String dbName,
                                        String optionGroupName,
                                        String dbParameterGroupName,
                                        Boolean publiclyAccessible,
                                        String dbSubnetGroupName,
                                        List<String> vpcSecurityGroupIds,
                                        Boolean copyTagsToSnapshot,
                                        Boolean iamDatabaseAuthenticationEnabled,
                                        Integer backupRetentionPeriod,
                                        String preferredBackupWindow,
                                        Boolean deletionProtection,
                                        Map<String, String> tags) {
}
