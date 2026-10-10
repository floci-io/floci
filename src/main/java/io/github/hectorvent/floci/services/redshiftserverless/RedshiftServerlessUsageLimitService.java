package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.UsageLimit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Redshift Serverless usage limits. They are stored and echoed, not enforced: Floci does not meter
 * RPU consumption, so no {@code breachAction} ever fires.
 *
 * <p>A limit belongs to a workgroup but is kept in its own store, so deleting a workgroup, from the
 * API or from CloudFormation, needs no hook here. Each read instead checks that the workgroup still
 * exists and drops the limit otherwise. A workgroup ARN carries a generated id, so a workgroup
 * recreated under the same name has a new ARN and never matches a limit left by its predecessor.
 */
@ApplicationScoped
public class RedshiftServerlessUsageLimitService implements Resettable {

    private static final Set<String> USAGE_TYPES = Set.of("serverless-compute", "cross-region-datasharing");
    private static final Set<String> PERIODS = Set.of("daily", "weekly", "monthly");
    private static final Set<String> BREACH_ACTIONS = Set.of("log", "emit-metric", "deactivate");
    private static final String DEFAULT_PERIOD = "monthly";
    private static final String DEFAULT_BREACH_ACTION = "log";
    private static final int PAGE_SIZE = 100;

    private final AccountAwareStorageBackend<UsageLimit> usageLimits;
    private final RegionResolver regionResolver;
    private final RedshiftServerlessService serverless;

    @Inject
    public RedshiftServerlessUsageLimitService(StorageFactory storageFactory, RegionResolver regionResolver,
                                               RedshiftServerlessService serverless) {
        this.usageLimits = storageFactory.create("redshiftserverless", "redshiftserverless-usagelimits.json",
                new TypeReference<Map<String, UsageLimit>>() {});
        this.regionResolver = regionResolver;
        this.serverless = serverless;
    }

    public synchronized UsageLimit createUsageLimit(String resourceArn, String usageType, Long amount,
                                                    String period, String breachAction, String region) {
        requireText(resourceArn, "resourceArn");
        requireText(usageType, "usageType");
        requireOneOf(usageType, USAGE_TYPES, "usageType");
        if (amount == null) {
            throw validation("amount is required.");
        }
        requirePositive(amount);
        String resolvedPeriod = period == null ? DEFAULT_PERIOD : requireOneOf(period, PERIODS, "period");
        String resolvedAction = breachAction == null
                ? DEFAULT_BREACH_ACTION : requireOneOf(breachAction, BREACH_ACTIONS, "breachAction");
        serverless.findWorkgroupByArn(resourceArn, region)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The resource " + resourceArn + " was not found.", 404));
        for (UsageLimit existing : live(region)) {
            if (resourceArn.equals(existing.getResourceArn()) && usageType.equals(existing.getUsageType())) {
                throw new AwsException("ConflictException",
                        "A usage limit of type " + usageType + " already exists for " + resourceArn + ".", 409);
            }
        }

        UsageLimit limit = new UsageLimit();
        limit.setUsageLimitId(UUID.randomUUID().toString());
        limit.setUsageLimitArn(regionResolver.buildArn("redshift-serverless", region,
                "usagelimit/" + limit.getUsageLimitId()));
        limit.setResourceArn(resourceArn);
        limit.setUsageType(usageType);
        limit.setAmount(amount);
        limit.setPeriod(resolvedPeriod);
        limit.setBreachAction(resolvedAction);
        usageLimits.put(storageKey(region, limit.getUsageLimitId()), limit);
        return new UsageLimit(limit);
    }

    public synchronized UsageLimit getUsageLimit(String usageLimitId, String region) {
        return new UsageLimit(require(usageLimitId, region));
    }

    public synchronized PaginatedResult<UsageLimit> listUsageLimits(String resourceArn, String usageType,
                                                                    String region, Integer maxResults,
                                                                    String nextToken) {
        if (usageType != null) {
            requireOneOf(usageType, USAGE_TYPES, "usageType");
        }
        List<UsageLimit> matching = live(region).stream()
                .filter(limit -> resourceArn == null || resourceArn.equals(limit.getResourceArn()))
                .filter(limit -> usageType == null || usageType.equals(limit.getUsageType()))
                .sorted(Comparator.comparing(UsageLimit::getUsageLimitId))
                .map(UsageLimit::new)
                .toList();
        return Pagination.paginate(matching, UsageLimit::getUsageLimitId, maxResults, nextToken,
                PAGE_SIZE, PAGE_SIZE, "InvalidPaginationException");
    }

    public synchronized UsageLimit updateUsageLimit(String usageLimitId, Long amount, String breachAction,
                                                    String region) {
        UsageLimit current = require(usageLimitId, region);
        if (amount != null) {
            requirePositive(amount);
        }
        if (breachAction != null) {
            requireOneOf(breachAction, BREACH_ACTIONS, "breachAction");
        }
        UsageLimit updated = new UsageLimit(current);
        if (amount != null) {
            updated.setAmount(amount);
        }
        if (breachAction != null) {
            updated.setBreachAction(breachAction);
        }
        usageLimits.put(storageKey(region, usageLimitId), updated);
        return new UsageLimit(updated);
    }

    public synchronized UsageLimit deleteUsageLimit(String usageLimitId, String region) {
        UsageLimit existing = require(usageLimitId, region);
        usageLimits.delete(storageKey(region, usageLimitId));
        return new UsageLimit(existing);
    }

    @Override
    public void clear() {
        usageLimits.clear();
    }

    private UsageLimit require(String usageLimitId, String region) {
        requireText(usageLimitId, "usageLimitId");
        return usageLimits.get(storageKey(region, usageLimitId))
                .filter(limit -> isLive(limit, region))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The usage limit " + usageLimitId + " was not found.", 404));
    }

    /** The region's limits whose workgroup is still there; any other is deleted as it is found. */
    private List<UsageLimit> live(String region) {
        return usageLimits.scan(key -> key.startsWith(region + "::")).stream()
                .filter(limit -> {
                    if (isLive(limit, region)) {
                        return true;
                    }
                    usageLimits.delete(storageKey(region, limit.getUsageLimitId()));
                    return false;
                })
                .toList();
    }

    private boolean isLive(UsageLimit limit, String region) {
        return serverless.findWorkgroupByArn(limit.getResourceArn(), region).isPresent();
    }

    private static String storageKey(String region, String usageLimitId) {
        return region + "::" + usageLimitId;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
    }

    private static String requireOneOf(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw validation(field + " must be one of " + allowed.stream().sorted().toList() + ".");
        }
        return value;
    }

    private static void requirePositive(long amount) {
        if (amount < 1) {
            throw validation("amount must be a positive number.");
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
