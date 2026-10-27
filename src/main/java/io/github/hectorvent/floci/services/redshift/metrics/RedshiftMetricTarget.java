package io.github.hectorvent.floci.services.redshift.metrics;

import io.github.hectorvent.floci.services.redshift.model.Cluster;

/** One cluster to publish metrics for, with the account and region its samples belong to. */
public record RedshiftMetricTarget(String accountId, String region, Cluster cluster, String proxyKey) { }
