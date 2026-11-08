package io.floci.aws.services.firehose;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The logical address in a Firehose {@code ClusterJDBCURL}. Floci routes by the first host label
 * (the cluster identifier or workgroup name) and ignores the host suffix and port, because a
 * Floci cluster is reachable on a Docker host and a dynamic port, not on the AWS-shaped name the
 * API requires.
 */
record RedshiftJdbcUrl(String clusterIdentifier, boolean serverless, String database) {

    private static final Pattern URL = Pattern.compile(
            "jdbc:(?:redshift|postgresql)://([A-Za-z0-9-]{1,63})\\.([A-Za-z0-9.\\-]+):\\d{1,5}/([a-zA-Z0-9_$-]+)");
    private static final Pattern SERVICE_LABEL = Pattern.compile("(?:^|\\.)redshift(-serverless)?\\.");

    static RedshiftJdbcUrl parse(String url) {
        Matcher matcher = URL.matcher(url == null ? "" : url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported ClusterJDBCURL: " + url);
        }
        Matcher service = SERVICE_LABEL.matcher(matcher.group(2));
        if (!service.find()) {
            throw new IllegalArgumentException("ClusterJDBCURL host is not a Redshift host: " + url);
        }
        return new RedshiftJdbcUrl(matcher.group(1), service.group(1) != null, matcher.group(3));
    }
}
