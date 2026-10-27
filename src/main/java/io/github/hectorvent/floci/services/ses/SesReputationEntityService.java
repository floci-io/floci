package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.services.ses.model.ReputationStatusRecord;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * SES v2 reputation entities. SES makes every tenant one, of type {@code RESOURCE}, referenced by
 * the tenant's full ARN, so the state lives on the {@link Tenant} record in
 * {@link SesTenantService}; this service validates the requests and derives the entity view.
 *
 * <p>Behaviour probe-confirmed against real SES (us-west-2, 2026-10-23): every error is a 400
 * {@code BadRequestException}, an unknown entity included. Floci has no reputation findings, so
 * the impact is always {@code NONE} and SES never manages a status of its own: the customer-managed
 * status is the aggregate.
 */
@ApplicationScoped
public class SesReputationEntityService {

    private static final Logger LOG = Logger.getLogger(SesReputationEntityService.class);

    static final String ENTITY_TYPE = "RESOURCE";
    static final String NO_IMPACT = "NONE";
    private static final String DEFAULT_POLICY = "standard";
    private static final Set<String> POLICIES = Set.of("standard", "strict", "none");
    private static final String POLICY_PREFIX = "reputation-policy/";
    private static final String POLICY_ACCOUNT = "aws"; // partition-literal: the account SES-managed policies belong to
    private static final List<String> SENDING_STATUSES = List.of("ENABLED", "REINSTATED", "DISABLED");
    // SES refuses any other impact, but MEDIUM is accepted as a filter although no entity carries it.
    private static final Set<String> IMPACTS = Set.of("NONE", "LOW", "MEDIUM", "HIGH");
    private static final String MANUAL_CAUSE = "Status manually updated.";

    /** A tenant seen as a reputation entity. */
    record ReputationEntity(String reference, String policy, ReputationStatusRecord customerManagedStatus,
                            String sendingStatusAggregate) {
    }

    private final SesTenantService tenantService;
    private final Clock clock;

    @Inject
    public SesReputationEntityService(SesTenantService tenantService, Clock clock) {
        this.tenantService = tenantService;
        this.clock = clock;
    }

    public ReputationEntity get(String type, String reference, String region) {
        requireType(type);
        requireReference(reference);
        return tenantService.findByArn(reference, region)
                .map(t -> entity(t, region))
                .orElseThrow(() -> entityNotFound(reference));
    }

    /**
     * SES checks the policy before anything else, the entity type included, and answers a missing
     * or unknown policy as it answers a reference that is not an ARN. Only the account and the
     * resource are checked: a policy of any partition or region, even a blank or unknown one, is
     * accepted and stored as given.
     */
    public void updatePolicy(String type, String reference, String policy, String region) {
        requirePolicy(policy);
        requireType(type);
        requireReference(reference);
        tenantService.updateByArn(reference, region, t -> t.withReputationPolicy(policy))
                .orElseThrow(() -> entityNotFound(reference));
        LOG.infov("Set reputation policy {0} on SES tenant {1}", policy, reference);
    }

    /** Setting the status it already has is accepted, and still refreshes the timestamp. */
    public void updateCustomerManagedStatus(String type, String reference, String status, String region) {
        requireType(type);
        requireReference(reference);
        if (status == null) {
            throw SmithyValidationErrors.badRequest(
                    SmithyValidationErrors.notNullWithoutValue("sendingStatus"));
        }
        if (!SENDING_STATUSES.contains(status)) {
            throw SmithyValidationErrors.badRequest("Value at 'sendingStatus' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [" + String.join(", ", SENDING_STATUSES) + "]");
        }
        ReputationStatusRecord record = new ReputationStatusRecord(status, MANUAL_CAUSE, Instant.now(clock));
        tenantService.updateByArn(reference, region, t -> t.withCustomerManagedStatus(record))
                .orElseThrow(() -> entityNotFound(reference));
        LOG.infov("Set customer-managed sending status {0} on SES tenant {1}", status, reference);
    }

    /**
     * SES checks the page size and an empty token first, then the filter, then the token. The filter
     * holds one key at most, its values are case-sensitive, and a token is bound to the filter it
     * came from. Entities are listed by reference.
     */
    public PaginatedResult<ReputationEntity> list(Map<String, String> filter, Integer pageSize, String nextToken,
                                                  String region) {
        SesListPaging paging = SesListPaging.V2_LIST_REPUTATION_ENTITIES;
        paging.checkRequest(pageSize, nextToken);
        if (filter.size() > 1) {
            throw new AwsException("BadRequestException", "Too many filters provided.", 400);
        }
        Map.Entry<String, String> only = filter.isEmpty() ? null : filter.entrySet().iterator().next();
        Predicate<ReputationEntity> matches = filterPredicate(only);
        List<ReputationEntity> entities = tenantService.listTenants(region).stream()
                .map(t -> entity(t, region))
                .filter(matches)
                .toList();
        String scope = only == null ? "" : only.getKey() + "=" + only.getValue();
        try {
            return paging.page(region, scope, entities, ReputationEntity::reference, pageSize, nextToken);
        } catch (AwsException e) {
            if (paging.issuedInRegion(nextToken, region)) {
                throw new AwsException("BadRequestException",
                        "Pagination token is bound to a different set of filtering parameters", 400);
            }
            throw e;
        }
    }

    private static Predicate<ReputationEntity> filterPredicate(Map.Entry<String, String> entry) {
        if (entry == null) {
            return e -> true;
        }
        String value = entry.getValue();
        return switch (entry.getKey()) {
            case "SENDING_STATUS" -> {
                requireFilterValue(entry.getKey(), SENDING_STATUSES.contains(value));
                yield e -> value.equals(e.sendingStatusAggregate());
            }
            case "ENTITY_TYPE" -> {
                requireFilterValue(entry.getKey(), ENTITY_TYPE.equals(value));
                yield e -> true;
            }
            case "REPUTATION_IMPACT" -> {
                requireFilterValue(entry.getKey(), IMPACTS.contains(value));
                yield e -> NO_IMPACT.equals(value);
            }
            default -> e -> e.reference().startsWith(value);
        };
    }

    private static void requireFilterValue(String key, boolean valid) {
        if (!valid) {
            throw new AwsException("BadRequestException", "Invalid reputation entity " + key + " specified", 400);
        }
    }

    private static ReputationEntity entity(Tenant tenant, String region) {
        String policy = tenant.reputationPolicy() != null
                ? tenant.reputationPolicy()
                : AwsArnUtils.Arn.of("ses", region, POLICY_ACCOUNT, POLICY_PREFIX + DEFAULT_POLICY).toString();
        return new ReputationEntity(tenant.tenantArn(), policy, tenant.customerManagedStatus(),
                tenant.sendingStatus());
    }

    private static void requireType(String type) {
        if (!ENTITY_TYPE.equals(type)) {
            throw SmithyValidationErrors.badRequest("Value at 'reputationEntityType' failed to satisfy "
                    + "constraint: Member must satisfy enum value set: [" + ENTITY_TYPE + "]");
        }
    }

    private static void requireReference(String reference) {
        if (!AwsArnUtils.isArnFor(reference, "ses")) {
            throw invalidIdentifier();
        }
    }

    private static void requirePolicy(String policy) {
        if (!AwsArnUtils.isArnFor(policy, "ses")) {
            throw invalidIdentifier();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(policy);
        if (!POLICY_ACCOUNT.equals(arn.accountId())
                || !arn.resource().startsWith(POLICY_PREFIX)
                || !POLICIES.contains(arn.resource().substring(POLICY_PREFIX.length()))) {
            throw invalidIdentifier();
        }
    }

    private static AwsException invalidIdentifier() {
        return new AwsException("BadRequestException", "Provided resource identifier is invalid", 400);
    }

    private static AwsException entityNotFound(String reference) {
        return new AwsException("BadRequestException", "ReputationEntity <" + reference + "> does not exist", 400);
    }
}
