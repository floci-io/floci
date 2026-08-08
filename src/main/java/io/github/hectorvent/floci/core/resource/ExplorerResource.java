package io.github.hectorvent.floci.core.resource;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/**
 * A single resource made visible to Resource Explorer 2.
 * <p>
 * One instance describes one AWS resource (a certificate, a queue, a cluster, etc.)
 *
 * <p>The fields are what the query language filters and keywords match against, so they
 * must be populated with real, consistent values for search to work. Filterable attributes:
 * {@code resourcetype:}, {@code service:}, {@code region:}, {@code accountid:}, {@code id:} (the
 * ARN), and the tag attributes ({@code tag:}, {@code tag.key:}, {@code tag.value:},
 * {@code application:} — the last reads the {@code awsApplication} tag). A bare keyword matches a
 * case-insensitive substring of {@link #arn}, {@link #resourceType}, {@link #service}, or
 * {@link #region}. See {@code ResourceFilter} for the exact matching rules.
 *
 * @param arn             full ARN of the resource, e.g.
 *                        {@code arn:aws:acm:us-east-1:123456789012:certificate/abc}. Doubles as the
 *                        resource's unique id ({@code id:} filter) and is matched by keyword search.
 * @param resourceType    Resource Explorer's type identifier in {@code service:subtype} form, e.g.
 *                        {@code acm:certificate}, {@code kafka:cluster}, {@code iam:user}. Must be
 *                        identical to the {@link SupportedResourceType#resourceType()} the provider
 *                        advertises for this resource, and unique across all providers.
 * @param service         AWS service namespace — the service segment of the ARN, e.g. {@code acm},
 *                        {@code kafka}, {@code cognito-idp}. This is the prefix of
 *                        {@link #resourceType}, not necessarily the floci module name.
 * @param owningAccountId 12-digit account id that owns the resource; usually
 *                        {@code AwsArnUtils.parse(arn).accountId()}.
 * @param region          AWS region the resource lives in, e.g. {@code us-east-1}; usually
 *                        {@code AwsArnUtils.parse(arn).region()}.
 * @param lastReportedAt  when Resource Explorer last observed the resource. floci has no scan
 *                        pipeline, so providers pass the resource's creation timestamp (falling back
 *                        to {@code Instant.now()} when unknown).
 * @param tags            resource tags as key→value; drives the tag filters and is emitted as the
 *                        {@code tags} property. Never {@code null} — pass {@code Map.of()} when the
 *                        resource has no tags.
 * @see SupportedResourceType
 * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_Resource.html">AWS API: Resource</a>
 * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/userguide/using-search-query-syntax.html">Resource Explorer search query syntax</a>
 */
@RegisterForReflection
public record ExplorerResource(
        String arn,
        String resourceType,
        String service,
        String region,
        String owningAccountId,
        Instant lastReportedAt,
        Map<String, String> tags
) {}
