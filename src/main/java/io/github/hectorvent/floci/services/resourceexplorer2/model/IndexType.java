package io.github.hectorvent.floci.services.resourceexplorer2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum IndexType {
    /**
     * A local index holds information only about resources in the same AWS Region as the index. An account can have one
     * local index per Region.
     */
    LOCAL,

    /**
     * The aggregator index receives replicated copies of the local index data from all Regions in which the account has
     * a local index. An account can have only one aggregator index, and it enables Resource Explorer to run
     * cross-Region searches.
     */
    AGGREGATOR
}
