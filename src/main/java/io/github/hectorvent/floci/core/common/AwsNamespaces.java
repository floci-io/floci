package io.github.hectorvent.floci.core.common;

/**
 * Canonical XML namespace URIs for every AWS service that uses the Query (XML) protocol.
 * Use these constants instead of inline string literals in handlers.
 */
public final class AwsNamespaces {

    public static final String SQS = "https://sqs.amazonaws.com/doc/2012-11-05/";
    public static final String SNS = "http://sns.amazonaws.com/doc/2010-03-31/";
    public static final String IAM = "https://iam.amazonaws.com/doc/2010-05-08/";
    public static final String STS = "https://sts.amazonaws.com/doc/2011-06-15/";
    public static final String RDS = "http://rds.amazonaws.com/doc/2014-10-31/";
    public static final String EC  = "http://elasticache.amazonaws.com/doc/2015-02-02/";
    public static final String CW  = "http://monitoring.amazonaws.com/doc/2010-08-01/";
    public static final String S3  = "http://s3.amazonaws.com/doc/2006-03-01/";
    public static final String SES = "http://ses.amazonaws.com/doc/2010-12-01/";
    public static final String EC2 = "http://ec2.amazonaws.com/doc/2016-11-15/";

    private AwsNamespaces() {}
}
