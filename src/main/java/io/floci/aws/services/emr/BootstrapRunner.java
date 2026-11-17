package io.floci.aws.services.emr;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Runs a cluster's bootstrap actions on one of its nodes in the EMR {@code docker} runner mode: one after the other,
 * in a container of the release's image, which is then kept as the image that node runs from, the primary node's being
 * the one the cluster's steps run in. The service calls it off the request thread; {@link EmrBootstrapRunner} is the
 * Docker implementation, and a test supplies its own.
 */
public interface BootstrapRunner {

    /**
     * Runs {@code bootstrap}'s actions in order, until one fails, and returns how they ended. Blocks until
     * then. A cluster {@code cancelled} reports as ending is stopped, and one it reports before the
     * container is up never starts.
     */
    Outcome run(Bootstrap bootstrap, BooleanSupplier cancelled);

    /** Stops each bootstrap of {@code clusterId}, its primary node's and its other nodes', now or once it is up. */
    void stop(String clusterId);

    /**
     * Removes {@code image}, which {@link #run} left, once its cluster no longer needs it: true when it is
     * gone, false when Docker refused, so the caller can try again.
     */
    boolean removeImage(String image);

    /**
     * Removes every image {@link #run} left in this deployment but those in {@code kept}, by id: after a restart,
     * the ones whose clusters are gone or have ended, and on a reset all of them.
     */
    void removeImages(Set<String> kept);

    /**
     * A cluster's bootstrap on one of its nodes: its cluster's id, which with the node names its container and its
     * image; its account and Region; its release, and the image template and Docker network to run it in (a null
     * template is the release's public EMR Serverless image, a null network the global default); the role of its EC2
     * instance profile, whose credentials the actions get; its actions; the S3 folder their logs go to, ending in a
     * slash (null for none), each action's in a folder of its own named by its number; where its instance metadata is
     * served; and the node it runs on, null for the primary node, else a core or task node's id as
     * {@link ClusterStorage.Node#id} has it, with that node's instance role, MASTER, CORE or TASK.
     */
    record Bootstrap(String clusterId, String accountId, String region, String releaseLabel, String imageTemplate,
                     String dockerNetwork, String role, List<Action> actions, String logFolder,
                     String metadataNetwork, String metadataAddress, String node, String instanceRole) {

        /** The primary node's bootstrap. */
        Bootstrap(String clusterId, String accountId, String region, String releaseLabel, String imageTemplate,
                  String dockerNetwork, String role, List<Action> actions, String logFolder) {
            this(clusterId, accountId, region, releaseLabel, imageTemplate, dockerNetwork, role, actions, logFolder,
                    null, null, null, "MASTER");
        }

        /** This bootstrap, run in {@code image}. */
        Bootstrap inImage(String image) {
            return new Bootstrap(clusterId, accountId, region, releaseLabel, image, dockerNetwork, role, actions,
                    logFolder, metadataNetwork, metadataAddress, node, instanceRole);
        }

        /**
         * This bootstrap, its container on {@code network}, its cluster's own, at {@code address}, a link-local one,
         * where its cluster's instance metadata relay answers it as its node, and on its steps' network too.
         */
        Bootstrap onMetadata(String network, String address) {
            return new Bootstrap(clusterId, accountId, region, releaseLabel, imageTemplate, dockerNetwork, role,
                    actions, logFolder, network, address, node, instanceRole);
        }

        /**
         * This bootstrap on the cluster's core or task node {@code id}, of instance role {@code nodeRole}, its logs in
         * {@code folder}, as on that node's own instance, before the relay is told where it is (see
         * {@link #onMetadata}).
         */
        Bootstrap onNode(String id, String nodeRole, String folder) {
            return new Bootstrap(clusterId, accountId, region, releaseLabel, imageTemplate, dockerNetwork, role,
                    actions, folder, null, null, id, nodeRole);
        }

        /** What tells this bootstrap's container, image and session from its cluster's others. */
        String name() {
            return node == null ? clusterId : clusterId + "-" + node;
        }
    }

    /** A bootstrap action: its name, the script it runs, and that script's arguments. */
    record Action(String name, String path, List<String> args) {
    }

    /**
     * How a bootstrap ended: every action succeeded, leaving the image of id {@code image}, which runs as it
     * is whatever registry Floci pulls images from; action {@code failedAction}, counted from 1, exited with
     * {@code exitCode}, or its script could not be read ({@code failure} says why); the bootstrap was
     * stopped; or it could not run at all ({@code failure} says why).
     */
    record Outcome(String image, Integer failedAction, Integer exitCode, boolean stopped, String failure) {

        public static Outcome succeeded(String image) {
            return new Outcome(image, null, null, false, null);
        }

        public static Outcome actionFailed(int action, int exitCode) {
            return new Outcome(null, action, exitCode, false, null);
        }

        public static Outcome notDownloaded(int action, String failure) {
            return new Outcome(null, action, null, false, failure);
        }

        public static Outcome halted() {
            return new Outcome(null, null, null, true, null);
        }

        public static Outcome couldNotRun(String failure) {
            return new Outcome(null, null, null, false, failure);
        }
    }
}
