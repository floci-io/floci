package io.floci.aws.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.redshift.model.ScheduledAction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Redshift scheduled actions of provisioned clusters, which pause or resume a cluster on an {@code at}
 * or {@code cron} schedule. A sweep ({@link #runScheduledActions}) runs the latest invocation of every
 * action whose schedule came due since it last looked. Resizing on a schedule is not supported.
 *
 * <p>An action belongs to a cluster but lives in its own store. Deleting the cluster removes its
 * actions through a removal listener, and every read drops an action whose cluster is gone, so a
 * cluster recreated under the same identifier never inherits its predecessor's actions.
 *
 * <p>The sweep records its checkpoint after attempting the invocation, so a crash in between can retry
 * that time once. After downtime only the latest due time runs, and nothing before an action was
 * created, rescheduled or enabled again is replayed. A failing invocation, such as pausing a cluster
 * that is already paused, is logged and kept as the action's last failure, and never stops the others.
 *
 * <p>Lock order: the action's lock, then this service's monitor. The removal listener takes neither,
 * because it runs under the cluster's lock, which a sweep takes while holding the action's lock.
 */
@ApplicationScoped
public class RedshiftScheduledActionService implements Resettable {

    private static final Logger LOG = Logger.getLogger(RedshiftScheduledActionService.class);
    private static final int MIN_MAX_RECORDS = 20;
    private static final int MAX_MAX_RECORDS = 100;

    private final AccountAwareStorageBackend<ScheduledAction> actions;
    private final RedshiftService redshift;
    private final RedshiftScheduledActionAuthorizer authorizer;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> actionLocks = new ConcurrentHashMap<>();

    @Inject
    public RedshiftScheduledActionService(StorageFactory storageFactory, RedshiftService redshift,
                                          RedshiftScheduledActionAuthorizer authorizer,
                                          RegionResolver regionResolver, Clock clock) {
        this.actions = storageFactory.create("redshift", "redshift-scheduled-actions.json",
                new TypeReference<Map<String, ScheduledAction>>() {});
        this.redshift = redshift;
        this.authorizer = authorizer;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    RedshiftScheduledActionService(StorageFactory storageFactory, RedshiftService redshift,
                                   RedshiftScheduledActionAuthorizer authorizer, RegionResolver regionResolver) {
        this(storageFactory, redshift, authorizer, regionResolver, Clock.systemUTC());
    }

    @PostConstruct
    void registerClusterRemoval() {
        redshift.onClusterRemoved(this::removeActionsOfCluster);
    }

    // Observing startup builds this lazy bean at boot, so its removal listener is registered before any
    // cluster can be deleted, also when the sweep is disabled or has not ticked yet.
    void onStart(@Observes StartupEvent ignored) {
        LOG.debug("Redshift scheduled actions registered for cluster removal");
    }

    /** Creates {@code request}. The target cluster must exist and the name must be unused. */
    public ScheduledAction createScheduledAction(ScheduledAction request) {
        return mutate(request.getScheduledActionName(), () -> {
            validate(request);
            if (liveActions().stream().anyMatch(existing ->
                    existing.getScheduledActionName().equals(request.getScheduledActionName()))) {
                throw new AwsException("ScheduledActionAlreadyExists",
                        "The scheduled action " + request.getScheduledActionName() + " already exists.", 400);
            }
            ScheduledAction action = new ScheduledAction(request);
            action.setActivatedAt(clock.instant());
            actions.put(request.getScheduledActionName(), action);
            // The cluster can be deleted between the check in validate and the write; its removal
            // listener has already run by then, so the action would be left behind.
            if (!clusterExists(action)) {
                actions.delete(request.getScheduledActionName());
                throw new AwsException("ClusterNotFound",
                        "Cluster " + request.getClusterIdentifier() + " not found", 404);
            }
            return new ScheduledAction(action);
        });
    }

    /**
     * Lists the account's actions filtered by the given members, which are all optional. {@code active}
     * keeps only the enabled actions when true and only the disabled ones when false. The time
     * filters keep the enabled actions with an invocation at or after {@code startTime}, or at or
     * before {@code endTime}.
     */
    public synchronized PaginatedResult<ScheduledAction> describeScheduledActions(
            String name, String targetActionType, Instant startTime, Instant endTime, Boolean active,
            List<String> clusterIdentifiers, List<String> iamRoles, Integer maxRecords, String marker) {
        int limit = maxRecords == null ? MAX_MAX_RECORDS : maxRecords;
        if (limit < MIN_MAX_RECORDS || limit > MAX_MAX_RECORDS) {
            throw new AwsException("InvalidParameterValue",
                    "MaxRecords must be between " + MIN_MAX_RECORDS + " and " + MAX_MAX_RECORDS + ".", 400);
        }
        Instant now = clock.instant();
        List<ScheduledAction> matching = liveActions().stream()
                .filter(action -> name == null || name.equals(action.getScheduledActionName()))
                .filter(action -> targetActionType == null || targetActionType.equals(action.getTargetActionType()))
                .filter(action -> active == null || action.isEnabled() == active)
                .filter(action -> clusterIdentifiers.isEmpty()
                        || clusterIdentifiers.contains(action.getClusterIdentifier()))
                .filter(action -> iamRoles.isEmpty() || iamRoles.contains(action.getIamRole()))
                .filter(action -> startTime == null
                        || RedshiftScheduledActionSchedule.next(action, startTime.minusNanos(1)).isPresent())
                .filter(action -> endTime == null || RedshiftScheduledActionSchedule.next(action, now)
                        .filter(next -> !next.isAfter(endTime)).isPresent())
                .sorted(Comparator.comparing(ScheduledAction::getScheduledActionName))
                .map(ScheduledAction::new)
                .toList();
        return Pagination.paginate(matching, ScheduledAction::getScheduledActionName, limit, marker,
                "redshift-scheduled-action",
                token -> new AwsException("InvalidParameterValue", "Invalid Marker.", 400));
    }

    /**
     * Applies the members {@code changes} carries; a null member is left as it was. A new schedule, or
     * enabling a disabled action, restarts the count from now so nothing earlier is replayed.
     */
    public ScheduledAction modifyScheduledAction(ScheduledAction changes, Boolean enable) {
        return mutate(changes.getScheduledActionName(), () -> {
            ScheduledAction current = require(changes.getScheduledActionName());
            ScheduledAction updated = new ScheduledAction(current);
            if (changes.getIamRole() != null) {
                updated.setIamRole(changes.getIamRole());
            }
            if (changes.getDescription() != null) {
                updated.setDescription(changes.getDescription());
            }
            if (changes.getSchedule() != null) {
                RedshiftScheduledActionSchedule.validate(changes.getSchedule());
                updated.setSchedule(changes.getSchedule());
                updated.setActivatedAt(clock.instant());
                updated.setLastClaimedInvocation(null);
            }
            if (changes.getTargetActionType() != null) {
                updated.setTargetActionType(changes.getTargetActionType());
                updated.setClusterIdentifier(changes.getClusterIdentifier());
            }
            if (changes.getStartTime() != null) {
                updated.setStartTime(changes.getStartTime());
            }
            if (changes.getEndTime() != null) {
                updated.setEndTime(changes.getEndTime());
            }
            if (enable != null) {
                if (enable && !current.isEnabled()) {
                    updated.setActivatedAt(clock.instant());
                }
                updated.setEnabled(enable);
            }
            validate(updated);
            actions.put(updated.getScheduledActionName(), updated);
            // Same race as in createScheduledAction: a deletion between validate and put leaves an orphan.
            if (!clusterExists(updated)) {
                actions.delete(updated.getScheduledActionName());
                throw new AwsException("ClusterNotFound",
                        "Cluster " + updated.getClusterIdentifier() + " not found", 404);
            }
            return new ScheduledAction(updated);
        });
    }

    public void deleteScheduledAction(String name) {
        mutate(name, () -> {
            require(name);
            actions.delete(name);
            return null;
        });
    }

    /** The next {@code count} invocations of {@code action} from now. */
    public List<Instant> upcomingInvocations(ScheduledAction action, int count) {
        return RedshiftScheduledActionSchedule.upcoming(action, clock.instant(), count);
    }

    /**
     * Runs the latest due invocation of every action whose time falls in {@code (checkpoint, now]}. A
     * deterministic entry point: the lifecycle scheduler supplies the clock and tests a fixed instant.
     */
    public void runScheduledActions(Instant now) {
        for (AccountEntry<ScheduledAction> entry : actions.scanAllAccountEntries(key -> true)) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            ReentrantLock lock = lockOf(entry.accountId(), entry.key());
            lock.lock();
            try {
                RequestScopes.runAs(entry.accountId(), regionOf(entry.accountId(), entry.value()),
                        () -> runScheduledAction(entry.accountId(), entry.key(), now));
            } catch (RuntimeException e) {
                LOG.warnv(e, "Scheduled action {0} in account {1} could not be processed",
                        entry.key(), entry.accountId());
            } finally {
                lock.unlock();
            }
        }
    }

    /** Runs under the action's lock, with the action's account as the request scope. */
    private void runScheduledAction(String accountId, String key, Instant now) {
        ScheduledAction action = actions.getForAccount(accountId, key).orElse(null);
        if (action == null) {
            return;
        }
        if (!redshift.clusterExists(accountId, action.getClusterIdentifier())) {
            actions.deleteForAccount(accountId, key);
            return;
        }
        if (action.getActivatedAt() == null) {
            ScheduledAction activated = new ScheduledAction(action);
            activated.setActivatedAt(now);
            actions.putAllAtomicallyForAccount(accountId, Map.of(key, activated));
            return;
        }
        Instant checkpoint = action.getLastClaimedInvocation() != null
                && action.getLastClaimedInvocation().isAfter(action.getActivatedAt())
                ? action.getLastClaimedInvocation() : action.getActivatedAt();
        Optional<Instant> due = RedshiftScheduledActionSchedule.latestDue(action, checkpoint, now);
        if (due.isEmpty()) {
            return;
        }
        Instant scheduled = due.get();
        ScheduledAction outcome = new ScheduledAction(action);
        outcome.setLastClaimedInvocation(scheduled);
        outcome.setLastFailure(null);
        try {
            invoke(action, accountId);
            outcome.setLastCompletedInvocation(scheduled);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Scheduled action {0} in account {1} failed at {2}",
                    action.getScheduledActionName(), accountId, scheduled);
            outcome.setLastFailure(String.valueOf(e.getMessage()));
        }
        recordOutcome(accountId, key, outcome);
    }

    private void invoke(ScheduledAction action, String accountId) {
        Optional<String> refusal = authorizer.refusal(action.getIamRole(), accountId, regionOf(accountId, action),
                action.getTargetActionType(), action.getClusterIdentifier());
        if (refusal.isPresent()) {
            throw new AwsException("UnauthorizedOperation", refusal.get(), 400);
        }
        if (ScheduledAction.PAUSE_CLUSTER.equals(action.getTargetActionType())) {
            redshift.pauseCluster(action.getClusterIdentifier());
        } else {
            redshift.resumeCluster(action.getClusterIdentifier());
        }
    }

    /** Stores the checkpoint and outcome only when the action is still the one that was run. */
    private void recordOutcome(String accountId, String key, ScheduledAction outcome) {
        // A failed write propagates to runScheduledActions, which logs it; swallowing it here would leave the
        // checkpoint behind and re-run the same invocation on every sweep.
        ScheduledAction current = actions.getForAccount(accountId, key).orElse(null);
        if (current != null && Objects.equals(current.getActivatedAt(), outcome.getActivatedAt())) {
            ScheduledAction recorded = new ScheduledAction(current);
            recorded.setLastClaimedInvocation(outcome.getLastClaimedInvocation());
            recorded.setLastCompletedInvocation(outcome.getLastCompletedInvocation());
            recorded.setLastFailure(outcome.getLastFailure());
            actions.putAllAtomicallyForAccount(accountId, Map.of(key, recorded));
        }
    }

    /** The region of the target cluster, which its ARN and the role check are scoped to. */
    private String regionOf(String accountId, ScheduledAction action) {
        return redshift.clusterRegion(accountId, action.getClusterIdentifier())
                .orElseGet(regionResolver::getDefaultRegion);
    }

    private void removeActionsOfCluster(String accountId, String clusterIdentifier) {
        for (AccountEntry<ScheduledAction> entry : actions.scanAllAccountEntries(key -> true)) {
            if (entry.accountId().equals(accountId)
                    && clusterIdentifier.equals(entry.value().getClusterIdentifier())) {
                actions.deleteForAccount(accountId, entry.key());
            }
        }
    }

    @Override
    public void clear() {
        actions.clear();
        actionLocks.clear();
    }

    private <T> T mutate(String name, Supplier<T> body) {
        ReentrantLock lock = lockOf(regionResolver.getAccountId(), name);
        lock.lock();
        try {
            synchronized (this) {
                return body.get();
            }
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockOf(String accountId, String key) {
        return actionLocks.computeIfAbsent(accountId + "|" + key, ignored -> new ReentrantLock());
    }

    private ScheduledAction require(String name) {
        ScheduledAction action = actions.get(name).orElse(null);
        if (action != null && !clusterExists(action)) {
            actions.delete(name);
            action = null;
        }
        if (action == null) {
            throw new AwsException("ScheduledActionNotFound", "The scheduled action " + name + " was not found.", 400);
        }
        return action;
    }

    /** The account's actions whose cluster is still there; any other is deleted as it is found. */
    private List<ScheduledAction> liveActions() {
        return actions.scan(key -> true).stream()
                .filter(action -> {
                    if (clusterExists(action)) {
                        return true;
                    }
                    actions.delete(action.getScheduledActionName());
                    return false;
                })
                .toList();
    }

    private boolean clusterExists(ScheduledAction action) {
        return redshift.clusterExists(regionResolver.getAccountId(), action.getClusterIdentifier());
    }

    private void validate(ScheduledAction action) {
        if (action.getScheduledActionName() == null || action.getScheduledActionName().isBlank()) {
            throw new AwsException("InvalidParameterValue", "ScheduledActionName is required.", 400);
        }
        if (action.getIamRole() == null || action.getIamRole().isBlank()) {
            throw new AwsException("InvalidParameterValue", "IamRole is required.", 400);
        }
        RedshiftScheduledActionSchedule.validate(action.getSchedule());
        if (action.getStartTime() != null && action.getEndTime() != null
                && !action.getEndTime().isAfter(action.getStartTime())) {
            throw new AwsException("InvalidSchedule", "EndTime must be after StartTime.", 400);
        }
        if (!ScheduledAction.PAUSE_CLUSTER.equals(action.getTargetActionType())
                && !ScheduledAction.RESUME_CLUSTER.equals(action.getTargetActionType())) {
            throw new AwsException("InvalidScheduledAction", "TargetAction must be PauseCluster or ResumeCluster.", 400);
        }
        // Throws ClusterNotFound for a cluster the account does not have.
        redshift.describeClusters(action.getClusterIdentifier());
    }
}
