package io.floci.aws.services.redshift;

import io.floci.aws.services.redshift.model.Cluster;

import java.util.List;

/**
 * What the audit log publisher needs to know about the resource whose activity it records, so that it does not
 * depend on the model of one kind of resource.
 *
 * @param identifier the identity the publisher registers the resource under and reports delivery outcomes for
 * @param name the name used in log stream names and in the file names of delivered S3 objects
 * @param logGroupPrefix the CloudWatch log group of a log type is {@code logGroupPrefix + "/" + logType}
 * @param arnResource the resource part of the ARN a delivery to S3 is authorized as, after {@code redshift:<region>:<account>:}
 * @param enabled whether logging is on
 * @param destinationType {@code cloudwatch} or {@code s3}; null with a bucket name means S3
 * @param bucketName the S3 bucket of an S3 delivery
 * @param s3KeyPrefix the key prefix of an S3 delivery
 * @param exports the log types delivered, or null for all of them
 * @param activityLoggingEnabled whether the user activity log is on, which a log type of its own also needs
 * @param loggingGeneration bumped whenever the logging configuration changes, so a late delivery outcome of an
 *                          earlier configuration can be told apart
 */
public record RedshiftAuditTarget(String identifier, String name, String logGroupPrefix, String arnResource,
                                  boolean enabled, String destinationType, String bucketName, String s3KeyPrefix,
                                  List<String> exports, boolean activityLoggingEnabled, long loggingGeneration) {

    public RedshiftAuditTarget {
        exports = exports == null ? null : List.copyOf(exports);
    }

    /** The target of a provisioned cluster, or null for no cluster. */
    public static RedshiftAuditTarget of(Cluster cluster) {
        if (cluster == null) {
            return null;
        }
        String identifier = cluster.getClusterIdentifier();
        return new RedshiftAuditTarget(identifier, identifier, "/aws/redshift/cluster/" + identifier,
                "cluster:" + identifier, cluster.isLoggingEnabled(), cluster.getLoggingDestinationType(),
                cluster.getLoggingBucketName(), cluster.getLoggingS3KeyPrefix(), cluster.getLoggingExports(),
                cluster.isUserActivityLoggingEnabled(), cluster.getLoggingGeneration());
    }
}
