package io.floci.aws.services.emr;

/**
 * The images a cluster runs in, in the EMR {@code docker} runner mode: its release's EMR Serverless image with its EMR
 * Serverless Hive image's HDFS, MapReduce and YARN added, and for a cluster with Hive its Hive too, for one with Pig
 * Tez and Apache Pig, as an EMR cluster's nodes hold every application it installs. A release of the AWS runtime for
 * Apache Spark, which has no Hive image, has one, with the Hadoop of another release's kept apart.
 * {@link EmrReleaseImages} is the Docker implementation, and a test supplies its own.
 */
public interface ReleaseImages {

    /**
     * The id of the image of {@code releaseLabel} made of {@code sparkImage} and {@code hiveImage}, with Hive or with
     * Hadoop's services alone, and with Pig or without, made first when the Docker daemon does not have it, which may
     * take as long as pulling the two, and downloading Pig; a failure is thrown.
     */
    default String image(String releaseLabel, String sparkImage, String hiveImage, boolean withHive,
                         boolean withPig) {
        return image(releaseLabel, sparkImage, hiveImage, withHive, withPig, null);
    }

    /**
     * {@link #image(String, String, String, boolean, boolean)}, with EMR's {@code trino-cli} for the Trino of
     * {@code trinoImage}, unless that is null, which pulling that image may take long too.
     */
    String image(String releaseLabel, String sparkImage, String hiveImage, boolean withHive, boolean withPig,
                 String trinoImage);

    /**
     * The step or the bootstrap that {@link #image} gave {@code imageId} to has ended, so a newer image of its kind may
     * remove it; until then it stays, as no container of it may be there yet to keep it. Once for each id given.
     */
    default void released(String imageId) {
    }
}
