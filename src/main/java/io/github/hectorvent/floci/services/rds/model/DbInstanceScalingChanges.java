package io.github.hectorvent.floci.services.rds.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Set;

/**
 * The compute, storage and engine-version members of ModifyDBInstance: the ones AWS applies with
 * an outage rather than in the background. A null member is one the request left out, which leaves
 * the instance's current value alone.
 *
 * <p>Floci applies them as soon as the request lands. AWS defers them to the preferred maintenance
 * window unless ApplyImmediately is set, and reports them under PendingModifiedValues until then;
 * Floci runs no maintenance window, so a deferred change would never be applied at all and every
 * read-back would report drift.
 */
@RegisterForReflection
public record DbInstanceScalingChanges(String dbInstanceClass,
                                       Integer allocatedStorage,
                                       String engineVersion,
                                       Boolean allowMajorVersionUpgrade) {

    /**
     * The engines whose AllocatedStorage member documents "the value supplied must be at least 10%
     * greater than the current value". SQL Server, the fourth engine Floci models, is not one of
     * them and takes the size it is given.
     */
    private static final Set<DatabaseEngine> TEN_PERCENT_MINIMUM_INCREASE =
            Set.of(DatabaseEngine.POSTGRES, DatabaseEngine.MYSQL, DatabaseEngine.MARIADB);

    public static DbInstanceScalingChanges unchanged() {
        return new DbInstanceScalingChanges(null, null, null, null);
    }

    /**
     * The same changes with AllocatedStorage resolved against the instance and every rule checked,
     * which leaves {@link #applyTo} pure assignment. AWS refuses a request as a whole, and the
     * instance a modify works on is the stored object rather than a copy, so a member written
     * before a later member is refused would stay written and read back as drift.
     */
    public DbInstanceScalingChanges resolveFor(DbInstance instance) {
        if (engineVersion != null && !engineVersion.isBlank()) {
            requireMajorVersionUpgradeAllowed(instance.getEngineVersion());
        }
        Integer resolvedStorage =
                allocatedStorage == null ? null : resolvedAllocatedStorage(instance);
        return new DbInstanceScalingChanges(dbInstanceClass, resolvedStorage, engineVersion,
                allowMajorVersionUpgrade);
    }

    /** Assigns the members of an instance resolved by {@link #resolveFor}. */
    public void applyTo(DbInstance instance) {
        if (dbInstanceClass != null && !dbInstanceClass.isBlank()) {
            instance.setDbInstanceClass(dbInstanceClass);
        }
        if (allocatedStorage != null) {
            instance.setAllocatedStorage(allocatedStorage);
        }
        if (engineVersion != null && !engineVersion.isBlank()) {
            instance.setEngineVersion(engineVersion);
        }
    }

    /**
     * The size the instance ends up with. RDS storage only ever grows: a smaller size is refused,
     * and an increase of less than 10% is rounded up to 10% rather than refused, both as the
     * AllocatedStorage member documents. The same size is left alone, since it is not a change.
     */
    private int resolvedAllocatedStorage(DbInstance instance) {
        int current = instance.getAllocatedStorage();
        if (allocatedStorage == current) {
            return current;
        }
        if (allocatedStorage < current) {
            throw new AwsException("InvalidParameterCombination",
                    "Invalid storage size for engine name " + instance.getEngineIdentifier()
                            + " and storage type " + DbInstance.DEFAULT_STORAGE_TYPE + ": "
                            + allocatedStorage, 400);
        }
        if (!TEN_PERCENT_MINIMUM_INCREASE.contains(instance.getEngine())) {
            return allocatedStorage;
        }
        return Math.max(allocatedStorage, Math.ceilDiv(current * 11, 10));
    }

    /**
     * AllowMajorVersionUpgrade states the rule as a constraint on EngineVersion: it "must be
     * allowed when specifying a value for the EngineVersion parameter that's a different major
     * version than the DB instance's current version".
     */
    private void requireMajorVersionUpgradeAllowed(String currentEngineVersion) {
        if (sameMajorVersion(currentEngineVersion, engineVersion)
                || Boolean.TRUE.equals(allowMajorVersionUpgrade)) {
            return;
        }
        throw new AwsException("InvalidParameterCombination",
                "The AllowMajorVersionUpgrade flag must be present when upgrading to a new major "
                        + "version.", 400);
    }

    private static boolean sameMajorVersion(String current, String requested) {
        if (current == null || requested == null) {
            return true;
        }
        return current.split("\\.")[0].equals(requested.split("\\.")[0]);
    }
}
