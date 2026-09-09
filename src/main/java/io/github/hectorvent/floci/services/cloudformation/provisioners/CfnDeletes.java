package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.core.common.AwsException;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Delete helpers shared by extracted provisioners.
 *
 * <p>A static utility rather than something on {@link ProvisionContext}, because the id-only
 * {@code CfnResourceProvisioner#delete(String, String, String)} never receives a context.
 */
public final class CfnDeletes {

    private static final Logger LOG = Logger.getLogger(CfnDeletes.class);

    private CfnDeletes() {
    }

    /**
     * Runs a delete, treating only the named AWS error codes as "already gone".
     *
     * <p>The tolerated codes are explicit on purpose. Stack deletion reports DELETE_FAILED when a
     * delete throws, which is how a real failure such as {@code BucketNotEmpty} reaches the user; a
     * helper that swallowed every exception would turn those into stacks that report a clean
     * deletion while the resource is still there. Pass exactly the not-found code the service
     * raises when the resource has already been removed, and let everything else propagate.
     *
     * @param description what is being deleted, for the debug log (for example {@code "DB proxy"})
     * @param physicalId the resource being deleted, for the debug log
     * @param delete the delete call itself
     * @param tolerateErrorCodes AWS error codes meaning the resource is already gone
     */
    public static void safeDelete(String description, String physicalId, Runnable delete,
                                  String... tolerateErrorCodes) {
        Set<String> tolerated = Set.of(tolerateErrorCodes);
        try {
            delete.run();
        } catch (AwsException e) {
            if (!tolerated.contains(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("{0} already gone, treating as deleted: {1}", description, physicalId);
        }
    }
}
