package io.github.hectorvent.floci.services.marketplace.model;

import java.time.Instant;

/** Local identity for an AWS Marketplace metering submission. */
public record MarketplaceMeteringRecord(
        String recordId,
        String region,
        String productCode,
        String customerIdentity,
        String dimension,
        int quantity,
        Instant hour) {}
