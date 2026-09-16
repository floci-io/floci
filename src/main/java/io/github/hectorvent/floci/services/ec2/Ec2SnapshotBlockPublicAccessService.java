package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;

/**
 * Block public access for snapshots: an account-level setting scoped to one region,
 * not a resource, so the region is the whole key and there is nothing to tag or
 * describe by id. The storage backend namespaces keys by the calling account, which
 * is what makes the setting account-and-region scoped.
 *
 * <p>A region that was never configured reads back {@code unblocked}, matching the
 * value AWS reports for an account that never enabled the setting.</p>
 */
@ApplicationScoped
public class Ec2SnapshotBlockPublicAccessService {

    private static final Logger LOG = Logger.getLogger(Ec2SnapshotBlockPublicAccessService.class);

    /** The state AWS reports for an account and region that never enabled the setting. */
    public static final String UNBLOCKED = "unblocked";

    static final String BLOCK_ALL_SHARING = "block-all-sharing";
    static final String BLOCK_NEW_SHARING = "block-new-sharing";

    /**
     * SnapshotBlockPublicAccessState also carries {@code unblocked}, but the EC2 model
     * documents it as invalid for EnableSnapshotBlockPublicAccess. Disabling goes through
     * DisableSnapshotBlockPublicAccess instead.
     */
    private static final Set<String> BLOCKING_STATES = Set.of(BLOCK_ALL_SHARING, BLOCK_NEW_SHARING);

    // region -> SnapshotBlockPublicAccessState
    private final StorageBackend<String, String> states;

    @Inject
    public Ec2SnapshotBlockPublicAccessService(StorageFactory storageFactory) {
        this(storageFactory.create("ec2", "ec2-snapshot-block-public-access.json",
                new TypeReference<Map<String, String>>() {}));
    }

    // Package-private for hermetic tests (pass an in-memory StorageBackend directly).
    Ec2SnapshotBlockPublicAccessService(StorageBackend<String, String> states) {
        this.states = states;
    }

    /**
     * Validate the requested State without touching stored state, so a caller can reject a
     * bad request before it honors DryRun. AWS reports an invalid parameter ahead of
     * DryRunOperation, which it only returns once the request could otherwise succeed.
     */
    public void validateEnableState(String state) {
        if (state == null || state.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter State", 400);
        }
        if (!BLOCKING_STATES.contains(state)) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + state + ") for parameter State is invalid. Valid values are "
                            + BLOCK_ALL_SHARING + " and " + BLOCK_NEW_SHARING, 400);
        }
    }

    public String enableSnapshotBlockPublicAccess(String region, String state) {
        validateEnableState(state);
        states.put(region, state);
        LOG.infov("Snapshot block public access in {0} set to {1}", region, state);
        return state;
    }

    public String disableSnapshotBlockPublicAccess(String region) {
        states.put(region, UNBLOCKED);
        LOG.infov("Snapshot block public access in {0} set to {1}", region, UNBLOCKED);
        return UNBLOCKED;
    }

    public String getSnapshotBlockPublicAccessState(String region) {
        return states.get(region).orElse(UNBLOCKED);
    }
}
