package io.floci.aws.services.acm.model;

import java.util.List;

/**
 * Result of paginated certificate listing.
 *
 * <p>Used by {@code ListCertificates} to return a page of certificates
 * along with a cursor token for fetching the next page.</p>
 *
 * @param certificates List of certificates for this page
 * @param nextToken Token for next page, or null if no more pages
 * @see <a href="https://docs.aws.amazon.com/acm/latest/APIReference/API_ListCertificates.html">AWS ACM ListCertificates</a>
 */
public record ListResult(
    List<Certificate> certificates,
    String nextToken
) {}
