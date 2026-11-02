package io.github.hectorvent.floci.core.common;

import java.util.List;
import java.util.Optional;

/**
 * Finds the ARN a service stores a resource under, for a type of the request-resources table whose ARN only that
 * service knows: after the name a request gives ({@code connection/<name>/<id>}), or as all of it, a request naming
 * the resource by members its ARN does not hold
 * ({@code scalable-target/<id>}, named by its service namespace, resource id and scalable dimension;
 * {@code workgroup/<id>}, named by its name). Also the ARN of a resource a request names through another of its
 * service's own, which the table gives no type (the cluster or workgroup a Data API session runs on).
 *
 * <p>The reader asks for every type of the namespace, not only for the ones whose ARN carries an id the service
 * mints, so a service may answer for a type whose ARN it does not mint: a key an alias names is the key the alias
 * points at.
 *
 * <p>Implemented by the service that owns such resources or whose requests name them (EventBridge, for its
 * connections and API destinations; Application Auto Scaling, for its scalable targets; Redshift Data, for the
 * Redshift Serverless workgroups it runs statements on and the targets of its sessions; Payment Cryptography, for
 * the key an alias names); looked up lazily by {@code ResourceArnBuilder} through {@code Instance<StoredResourceArns>}
 * for the namespace a request is of, so IAM never depends on that service directly.</p>
 */
public interface StoredResourceArns {

    /** The namespace of the resource types it finds, as the table names it ({@code events}). */
    String namespace();

    /**
     * The ARN the caller's resource of the type in the Region is stored under, named by {@code names}, the values the
     * request names it by in the order the table reads them: a connection's name, a scalable target's service
     * namespace, resource id and scalable dimension, or a workgroup's name or ARN; empty when there is none, or the
     * type is not one it finds.
     */
    Optional<String> find(String type, List<String> names, String region);

    /**
     * The ARN of the resource the caller's resource named by {@code value} in its {@code member} runs on in the
     * Region, as a request naming it runs there (the cluster or workgroup of a Data API session, by its
     * {@code SessionId}); empty when the caller has none of that name, or the member is not one it reads.
     */
    default Optional<String> through(String member, String value, String region) {
        return Optional.empty();
    }

    /**
     * Resolves an explicit {@code find} selector. An empty list indicates an incomplete selector, which a service
     * may reject before IAM evaluation. This is distinct from an ordinary name filling an ARN's variables:
     * callback tokens require validation, but state-machine names do not name callback tokens.
     */
    default Optional<String> findByMembers(String type, List<String> names, String region) {
        return find(type, names, region);
    }
}
