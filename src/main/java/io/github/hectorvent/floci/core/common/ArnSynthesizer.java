package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * SPI for services whose stored models carry tags but no ARN of their own.
 *
 * <p>{@code TaggedResourceScanner} reads every service's persisted model state to answer
 * {@code GetResources}. Most models carry their own ARN as a field, and the scanner reads it
 * directly. EC2 is the large exception: real AWS EC2 API models do not include ARNs, only ids
 * ({@code vol-…}, {@code i-…}, {@code sg-…}), and floci's models mirror that faithfully. The
 * ARN has to be assembled from the id, which needs service-specific knowledge of the id-prefix
 * to resource-type mapping.
 *
 * <p>Implementations are discovered by CDI: annotate {@code @ApplicationScoped} and implement
 * this interface. There is no registration step. An implementation that does not recognise the
 * store it is handed must return {@link Optional#empty()} — the scanner tries every synthesizer
 * and takes the first non-empty answer.
 *
 * <p>Implementations must produce the ARN real AWS would produce for that resource, including
 * omitting the account or region segment where AWS omits it (EC2 snapshots and images carry no
 * account). A wrong ARN is worse than no ARN: callers join on it.
 */
public interface ArnSynthesizer {

    /**
     * Attempts to build the ARN for one stored model.
     *
     * @param serviceName the service name the store was created under ({@code "ec2"}, {@code "iam"}, …)
     * @param storeName   the store's file name ({@code "s3-buckets.json"}), so a synthesizer can
     *                    target one store of a service that owns several — {@code s3-buckets.json}
     *                    and {@code s3-objects.json} need different ARN shapes
     * @param logicalKey  the storage key with the account prefix stripped, as the owning service
     *                    wrote it (EC2 uses {@code <region>::<id>})
     * @param accountId   the account the entry is stored under
     * @param node        the model serialized to a JSON tree
     * @return the resource's ARN, or empty when this synthesizer does not handle it
     */
    Optional<String> synthesize(String serviceName, String storeName, String logicalKey,
                                String accountId, JsonNode node);
}
