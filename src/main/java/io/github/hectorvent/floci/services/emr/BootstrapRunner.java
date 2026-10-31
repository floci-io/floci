package io.github.hectorvent.floci.services.emr;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Runs a cluster's bootstrap actions in the EMR {@code docker} runner mode: one after the other, in one
 * container of the release's image, which is then kept as the image the cluster's steps run in. The
 * service calls it off the request thread; {@link EmrBootstrapRunner} is the Docker implementation, and a
 * test supplies its own.
 */
public interface BootstrapRunner {

    /**
     * Runs {@code bootstrap}'s actions in order, until one fails, and returns how they ended. Blocks until
     * then. A cluster {@code cancelled} reports as ending is stopped, and one it reports before the
     * container is up never starts.
     */
    Outcome run(Bootstrap bootstrap, BooleanSupplier cancelled);

    /** Stops the bootstrap of {@code clusterId}, now or as soon as its container is up. */
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
     * A cluster's bootstrap: its id, which names its container and its image; its account and Region; its
     * release, and the image template and Docker network to run it in (a null template is the release's
     * public EMR Serverless image, a null network the global default); the role of its EC2 instance profile,
     * whose credentials the actions get; its actions; and the S3 folder their logs go to, ending in a slash
     * (null for none), each action's in a folder of its own named by its number.
     */
    record Bootstrap(String clusterId, String accountId, String region, String releaseLabel, String imageTemplate,
                     String dockerNetwork, String role, List<Action> actions, String logFolder) {
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
