package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.SesRecommendations.Finding;
import io.github.hectorvent.floci.services.ses.model.Identity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * SES v2 ListRecommendations, derived on every call from the region's verified domains (see
 * {@link SesRecommendations}). Probe-confirmed against real SES: a region without Virtual Deliverability
 * Manager answers an empty list rather than an error, verified domains notwithstanding (2026-10-29);
 * recommendations come newest first; and a TYPE filter makes SES ignore an IMPACT filter sent with it
 * (2026-10-27).
 *
 * <p>Nothing is stored, so every recommendation is OPEN: a finding that clears disappears rather than
 * turning FIXED. A recommendation is dated from its identity's creation and reports the time of the
 * request as its last update, as SES re-evaluates every recommendation daily.
 */
@ApplicationScoped
public class SesRecommendationService {

    private static final List<String> TYPES =
            List.of("DKIM", "DMARC", "SPF", "BIMI", "COMPLAINT", "BOUNCE", "FEEDBACK_3P", "IP_LISTING");
    private static final List<String> IMPACTS = List.of("LOW", "HIGH");
    private static final List<String> STATUSES = List.of("OPEN", "FIXED");
    private static final String OPEN = "OPEN";

    private final SesIdentityService identityService;
    private final SesAccountService accountService;
    private final RegionResolver regionResolver;
    private final Clock clock;

    public record Recommendation(String resourceArn, String type, String description, String status,
                                 Instant createdTimestamp, Instant lastUpdatedTimestamp, String impact) {}

    @Inject
    public SesRecommendationService(SesIdentityService identityService, SesAccountService accountService,
                                    RegionResolver regionResolver, Clock clock) {
        this.identityService = identityService;
        this.accountService = accountService;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    /**
     * The controller has already checked the filter's size and keys. The page size and an empty token
     * come next, then the filter values in key order, and last the token. Without VDM the request is
     * still validated, the order of the gate against the validation being unprobed.
     */
    public PaginatedResult<Recommendation> list(Map<String, String> filter, Integer pageSize, String nextToken,
                                                String region) {
        SesListPaging paging = SesListPaging.V2_LIST_RECOMMENDATIONS;
        paging.checkRequest(pageSize, nextToken);
        Predicate<Recommendation> matches = filterPredicate(filter);
        List<Recommendation> recommendations = accountService.isVdmEnabled(region)
                ? derive(region).stream().filter(matches).toList()
                : List.of();
        return paging.page(region, scope(filter), recommendations,
                r -> SesListPaging.newestFirst(r.createdTimestamp(), r.resourceArn() + "#" + r.description()),
                pageSize, nextToken);
    }

    /** The ARN goes last: the other values never hold the separator, so no ARN can pass for another filter. */
    private static String scope(Map<String, String> filter) {
        if (filter.isEmpty()) {
            return "";
        }
        return Objects.toString(filter.get("TYPE"), "") + "/" + Objects.toString(filter.get("IMPACT"), "")
                + "/" + Objects.toString(filter.get("STATUS"), "") + "/"
                + Objects.toString(filter.get("RESOURCE_ARN"), "");
    }

    private List<Recommendation> derive(String region) {
        Instant now = Instant.now(clock);
        String accountId = regionResolver.getAccountId();
        List<Identity> advised = identityService.listIdentities("Domain", region).stream()
                .map(identity -> identityService.withCurrentStatus(identity, region))
                .filter(SesRecommendations::isAdvised)
                .toList();
        if (advised.isEmpty()) {
            return List.of();
        }
        Set<String> txtRecordNames = identityService.txtRecordNames();
        List<Recommendation> recommendations = new ArrayList<>();
        for (Identity identity : advised) {
            String domain = identity.getIdentity();
            String arn = AwsArnUtils.Arn.of("ses", region, accountId, "identity/" + domain).toString();
            List<Finding> findings = SesRecommendations.findings(identity,
                    txtRecordNames.contains(
                            SesIdentityService.normalizeDnsName(SesRecommendations.dmarcRecordName(domain))),
                    txtRecordNames.contains(
                            SesIdentityService.normalizeDnsName(SesRecommendations.bimiRecordName(domain))));
            for (Finding finding : findings) {
                recommendations.add(new Recommendation(arn, finding.type(), finding.description(), OPEN,
                        identity.getCreatedAt(), now, finding.impact()));
            }
        }
        return recommendations;
    }

    private Predicate<Recommendation> filterPredicate(Map<String, String> filter) {
        Predicate<Recommendation> matches = r -> true;
        String type = filter.get("TYPE");
        if (type != null) {
            requireFilterValue("TYPE", TYPES.contains(type));
            matches = matches.and(r -> type.equals(r.type()));
        }
        String impact = filter.get("IMPACT");
        if (impact != null) {
            requireFilterValue("IMPACT", IMPACTS.contains(impact));
            if (type == null) {
                matches = matches.and(r -> impact.equals(r.impact()));
            }
        }
        String status = filter.get("STATUS");
        if (status != null) {
            requireFilterValue("STATUS", STATUSES.contains(status));
            matches = matches.and(r -> status.equals(r.status()));
        }
        String resourceArn = filter.get("RESOURCE_ARN");
        if (resourceArn != null) {
            requireOwnArn(resourceArn);
            matches = matches.and(r -> resourceArn.equals(r.resourceArn()));
        }
        return matches;
    }

    private static void requireFilterValue(String key, boolean valid) {
        if (!valid) {
            throw new AwsException("BadRequestException", "Invalid recommendation " + key + " specified", 400);
        }
    }

    private void requireOwnArn(String resourceArn) {
        if (!AwsArnUtils.isArnFor(resourceArn, "ses")) {
            throw new AwsException("BadRequestException", "Filter resource is invalid", 400);
        }
        if (AwsArnUtils.isForeignAccount(AwsArnUtils.parse(resourceArn), regionResolver.getAccountId())) {
            throw new AwsException("BadRequestException",
                    "Provided resource ARN does not match the caller account", 400);
        }
    }
}
