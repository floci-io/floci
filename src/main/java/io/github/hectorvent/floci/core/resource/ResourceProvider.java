package io.github.hectorvent.floci.core.resource;

import java.util.List;
import java.util.Set;

/**
 * Extension point that lets an individual AWS service emulator expose its resources to the Resource
 * Explorer 2 emulator. Implement this on a service that owns resources you want to be discoverable
 * via {@code Search}, {@code ListResources}, and {@code ListSupportedResourceTypes}.
 * <p>
 * Implementations must be CDI beans (usually {@code @ApplicationScoped} service classes).
 * {@code ResourceExplorer2Service} iterates {@code ResourceProvider}s:
 * <ul>
 *   <li>{@code Search} / {@code ListResources} concatenate {@link #getResources()} from all
 *       providers, apply the query filter, then paginate. {@code Search} returns at most the first
 *       1000 matches; {@code ListResources} has no such cap and pages through every match, but omits
 *       its {@code NextToken} when {@code MaxResults} is 1000. floci mirrors both behaviors.</li>
 *   <li>{@code ListSupportedResourceTypes} concatenates {@link #getSupportedResourceTypes()} from
 *       all providers and deduplicates by {@link SupportedResourceType#resourceType()}.</li>
 * </ul>
 *
 * <h2>Implementing</h2>
 * <ol>
 *   <li>{@link #getResources()}: scan your storage backend and map each stored entity to an
 *       {@link ExplorerResource}.</li>
 *   <li>{@link #getSupportedResourceTypes()}: return one {@link SupportedResourceType} per distinct
 *       {@code resourceType} string you emit.</li>
 *   <li>Keep the {@code resourceType} string identical between the two methods — a resource whose
 *       type is not advertised will still be searchable, but callers listing supported types would
 *       not see it, which is inconsistent with AWS.</li>
 * </ol>
 *
 * <p>See {@code AcmService}, {@code MskService}, or {@code IamService} (which exposes two types)
 * for reference implementations.
 *
 * @see ExplorerResource
 * @see SupportedResourceType
 * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/userguide/getting-started-terms-and-concepts.html">Resource Explorer terms and concepts</a>
 * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_Search.html">AWS API: Search</a>
 */
public interface ResourceProvider {

    /**
     * All resources this service currently own.
     * <p>
     * Build the list from live storage rather
     * than caching. Return an empty list when the service holds nothing; never return {@code null}.
     * Ordering is not significant — the service applies its own pagination.
     */
    List<ExplorerResource> getResources();

    /**
     * The resource types this provider can produce.
     * <p>
     * One entry per distinct
     * {@link ExplorerResource#resourceType()} value returned by {@link #getResources()}.
     * The set is fixed for a given service, so returning a
     * literal {@code Set.of(...)} is the norm. Each {@code resourceType} must be globally unique
     * across providers.
     */
    Set<SupportedResourceType> getSupportedResourceTypes();
}
