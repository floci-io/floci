package io.floci.aws.services.redshift.metrics;

import io.floci.aws.services.redshift.model.Cluster;

/** One cluster to publish metrics for, with the account and region its samples belong to. */
public record RedshiftMetricTarget(String accountId, String region, Cluster cluster, String proxyKey) { }
