package io.github.hectorvent.floci.services.opensearch.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Who owns the storage a domain's container mounts, stamped on the domain record when
 * the storage is selected. Deletion acts on this marker rather than recomputing
 * ownership from whatever configuration holds at delete time: a recomputed name no
 * longer matches the persisted one as soon as the resource namespace changes, and a
 * name an operator picked through {@code floci.services.opensearch.data-volume} can
 * be spelled exactly like one Floci derives.
 *
 * <p>Null on records written before this field existed, which predate configured data volumes
 * and therefore always have Floci-managed storage.</p>
 */
@RegisterForReflection
public enum VolumeOwnership {

    /** Floci derived the per-domain volume name; deletion removes it per storage policy. */
    FLOCI_MANAGED,

    /** The operator named the volume through {@code floci.services.opensearch.data-volume}; never removed. */
    OPERATOR_NAMED,

    /** The operator bind-mounted a host path through {@code floci.services.opensearch.data-volume}; never removed. */
    OPERATOR_BIND
}
