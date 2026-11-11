package io.floci.aws.services.emr;

/**
 * The images a cluster runs in, in the EMR {@code docker} runner mode: two per release, its EMR Serverless image with
 * its EMR Serverless Hive image's HDFS, MapReduce and YARN added, and for a cluster with Hive its Hive too, as an EMR
 * cluster's nodes hold every application it installs; a release of the AWS runtime for Apache Spark, which has no Hive
 * image, has one, with the Hadoop of another release's kept apart. {@link EmrReleaseImages} is the Docker implementation,
 * and a test supplies its own.
 */
public interface ReleaseImages {

    /**
     * The id of the image of {@code releaseLabel} made of {@code sparkImage} and {@code hiveImage}, with Hive or with
     * Hadoop's services alone, made first when the Docker daemon does not have it, which may take as long as pulling
     * the two; a failure is thrown.
     */
    String image(String releaseLabel, String sparkImage, String hiveImage, boolean withHive);
}
