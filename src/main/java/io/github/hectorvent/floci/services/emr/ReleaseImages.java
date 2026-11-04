package io.github.hectorvent.floci.services.emr;

/**
 * The images a cluster with Hive runs in, in the EMR {@code docker} runner mode: one per release, its EMR
 * Serverless image with its EMR Serverless Hive image's Hive added, as an EMR cluster's nodes hold every
 * application it installs. {@link EmrReleaseImages} is the Docker implementation, and a test supplies its own.
 */
public interface ReleaseImages {

    /**
     * The id of the image of {@code releaseLabel} made of {@code sparkImage} and {@code hiveImage}, made first when the
     * Docker daemon does not have it, which may take as long as pulling the two; a failure is thrown.
     */
    String image(String releaseLabel, String sparkImage, String hiveImage);
}
