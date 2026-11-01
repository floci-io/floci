package io.github.hectorvent.floci.core.common;

import java.util.List;
import java.util.Optional;

/**
 * Finds the ARN a service stores a resource under, for a type of the request-resources table whose ARN carries an id
 * the service mints, which only the service knows: after the name a request gives ({@code connection/<name>/<id>}),
 * or as all of it, a request naming the resource by members its ARN does not hold
 * ({@code scalable-target/<id>}, named by its service namespace, resource id and scalable dimension).
 *
 * <p>Implemented by the service that owns such resources (EventBridge, for its connections and API destinations;
 * Application Auto Scaling, for its scalable targets); looked up lazily by {@code ResourceArnBuilder} through
 * {@code Instance<StoredResourceArns>} for the namespace a request is of, so IAM never depends on that service
 * directly.</p>
 */
public interface StoredResourceArns {

    /** The namespace of the resource types it finds, as the table names it ({@code events}). */
    String namespace();

    /**
     * The ARN the caller's resource of the type in the Region is stored under, named by {@code names}, the values the
     * request names it by in the order the table reads them: a connection's name, or a scalable target's service
     * namespace, resource id and scalable dimension; empty when there is none, or the type is not one it finds.
     */
    Optional<String> find(String type, List<String> names, String region);
}
