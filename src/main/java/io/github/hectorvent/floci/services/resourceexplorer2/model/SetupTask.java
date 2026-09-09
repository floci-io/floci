package io.github.hectorvent.floci.services.resourceexplorer2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

/**
 * A completed multi-Region setup task, addressable by {@code TaskId} through
 * {@code GetResourceExplorerSetup}.
 *
 * <p>The per-Region outcomes are recorded when the task runs rather than recomputed on read: a
 * delete task removes the very indexes and views its status describes, so there would be nothing
 * left to derive them from afterwards.
 *
 * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_CreateResourceExplorerSetup.html">
 *     AWS API: CreateResourceExplorerSetup</a>
 */
@RegisterForReflection
public record SetupTask(String taskId, List<RegionOutcome> regions, Instant createdAt) {

    /** One Region's index and view outcome. Either may be null when the task did not touch it. */
    @RegisterForReflection
    public record RegionOutcome(String region, SetupOutcome index, SetupOutcome view) {}
}
