package io.github.hectorvent.floci.core.resource;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One resource type a {@link ResourceProvider} can produce.
 * <p>
 * This is the type-level counterpart to {@link ExplorerResource}
 * (which is an instance): a provider returns one of these per distinct
 * {@link ExplorerResource#resourceType()} value it emits.
 *
 * <p>The Resource Explorer 2 service collects these from every provider, deduplicates by {@link #resourceType}.
 *
 * @param resourceType type identifier in {@code service:subtype} form, e.g. {@code acm:certificate},
 *                     {@code kafka:cluster}, {@code iam:user}. Must exactly match the
 *                     {@link ExplorerResource#resourceType()} used for the corresponding resources,
 *                     and be unique across all providers — the service uses this string as the
 *                     deduplication key.
 * @param service      AWS service namespace, i.e. the segment before the colon in
 *                     {@link #resourceType} (e.g. {@code acm}, {@code kafka}, {@code cognito-idp}).
 * @param supportsTags whether this type carries tags. Advisory metadata describing the type; the
 *                     Resource Explorer emulator currently treats every exposed type as taggable
 *                     regardless (the {@code resourcetype:supports=tags} filter is a pass-through),
 *                     so set it truthfully but do not expect it to gate behavior today.
 * @see ExplorerResource
 * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_ListSupportedResourceTypes.html">AWS API: ListSupportedResourceTypes</a>
 */
@RegisterForReflection
public record SupportedResourceType(
        String resourceType,
        String service,
        boolean supportsTags
) {}
