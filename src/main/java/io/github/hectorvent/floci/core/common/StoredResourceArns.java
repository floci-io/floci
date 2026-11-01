package io.github.hectorvent.floci.core.common;

import java.util.Optional;

/**
 * Finds the ARN a service stores a resource under, for a type of the request-resources table whose ARN only that
 * service knows: EventBridge's connections and API destinations, whose ARN follows the name with an id it mints
 * ({@code connection/<name>/<id>}), and Payment Cryptography's keys, which an alias names and the alias itself
 * ({@code alias/<name>}).
 *
 * <p>Implemented by the service that owns such resources (EventBridge, for its connections and API destinations;
 * Payment Cryptography, for its keys and aliases); looked up lazily by {@code ResourceArnBuilder} through
 * {@code Instance<StoredResourceArns>} for the namespace a request is of, so IAM never depends on that service
 * directly.</p>
 */
public interface StoredResourceArns {

    /** The namespace of the resource types it finds, as the table names it ({@code events}). */
    String namespace();

    /**
     * The ARN the caller's resource of the type, named {@code name} in the Region, is stored under; empty when there
     * is none, or the type is not one it finds.
     */
    Optional<String> find(String type, String name, String region);
}
