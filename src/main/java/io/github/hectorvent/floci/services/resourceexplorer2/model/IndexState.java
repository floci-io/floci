package io.github.hectorvent.floci.services.resourceexplorer2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum IndexState {
    /** The index is being created and is not yet available for use. */
    CREATING,

    /** The index exists and is available to receive and respond to search queries. */
    ACTIVE,

    /** The index is being deleted and can no longer be used. */
    DELETING,

    /** The index has been deleted. */
    DELETED,

    /**
     * The index is being changed, typically when converting between {@link IndexType#LOCAL} and
     * {@link IndexType#AGGREGATOR}. The index remains usable during this transition.
     */
    UPDATING
}
