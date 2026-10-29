package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.ScheduledAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Redshift Serverless scheduled actions. A sweep ({@link #runScheduledActions}) takes the snapshot of
 * every action whose schedule came due since it last looked.
 *
 * <p>Required members, name patterns and lengths come from the AWS model through
 * {@link RedshiftServerlessRequestShapes}; what the model cannot express (the schedule union, the time
 * window, the retention range and the namespaces that must exist) is checked here.
 *
 * <p>An action belongs to a namespace but lives in its own store, so deleting the namespace needs
 * no hook here. Each read checks that the namespace is still there and drops the action otherwise.
 *
 * <p>The sweep records its checkpoint with a normal atomic write after attempting the snapshot.
 * A crash before that write, or before a hybrid flush, can cause the time to be retried. Snapshot names
 * derive from the scheduled time, so an existing snapshot prevents a duplicate on retry. After downtime
 * only the latest due time is attempted, and nothing before an action was created, scheduled again or
 * enabled again is replayed. Memory storage forgets actions on restart.
 *
 * <p>Lock order: the action's lock, then this service's monitor, then the Serverless service's. A
 * mutation and a sweep of the same action hold its lock, so neither sees the other half done; the
 * monitor is never held while a snapshot is dumped, so reads stay available during one.
 */
@ApplicationScoped
public class RedshiftServerlessScheduledActionService implements Resettable {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessScheduledActionService.class);
    private static final int MAX_RETENTION_DAYS = 3653;
    private static final int MAX_SNAPSHOT_NAME_LENGTH = 255;
    private static final DateTimeFormatter INVOCATION_SUFFIX =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final AccountAwareStorageBackend<ScheduledAction> actions;
    private final RedshiftServerlessService serverless;
    private final RedshiftServerlessScheduledActionAuthorizer authorizer;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> actionLocks = new ConcurrentHashMap<>();

    @Inject
    public RedshiftServerlessScheduledActionService(StorageFactory storageFactory,
                                                    RedshiftServerlessService serverless,
                                                    RedshiftServerlessScheduledActionAuthorizer authorizer,
                                                    RegionResolver regionResolver,
                                                    Clock clock) {
        this.actions = storageFactory.create("redshiftserverless", "redshiftserverless-scheduledactions.json",
                new TypeReference<Map<String, ScheduledAction>>() {});
        this.serverless = serverless;
        this.authorizer = authorizer;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    RedshiftServerlessScheduledActionService(StorageFactory storageFactory,
                                            RedshiftServerlessService serverless,
                                            RedshiftServerlessScheduledActionAuthorizer authorizer,
                                            RegionResolver regionResolver) {
        this(storageFactory, serverless, authorizer, regionResolver, Clock.systemUTC());
    }

    /**
     * Creates the action described by {@code request}. {@code enabled} defaults to true. The target
     * namespace of the snapshot and the owning namespace must both exist.
     */
    public ScheduledAction createScheduledAction(ScheduledAction request, String region) {
        return mutate(region, request.getScheduledActionName(), () -> {
            requireNamespace(request.getNamespaceName(), region);
            validateSchedule(request);
            validateTarget(request, region);
            validateWindow(request);
            if (live(region).stream().anyMatch(existing ->
                    existing.getScheduledActionName().equals(request.getScheduledActionName()))) {
                throw ServerlessSupport.conflict("The scheduled action " + request.getScheduledActionName()
                        + " already exists.");
            }

            ScheduledAction action = new ScheduledAction(request);
            action.setOwnerId(namespaceIdOf(request.getNamespaceName(), region));
            action.setScheduledActionUuid(UUID.randomUUID().toString());
            action.setEnabled(request.getEnabled() == null || request.getEnabled());
            action.setActivatedAt(clock.instant());
            actions.put(storageKey(region, action.getScheduledActionName()), action);
            return new ScheduledAction(action);
        });
    }

    public synchronized ScheduledAction getScheduledAction(String scheduledActionName, String region) {
        return new ScheduledAction(require(scheduledActionName, region));
    }

    public synchronized PaginatedResult<ScheduledAction> listScheduledActions(String namespaceName, String region,
                                                                              Integer maxResults, String nextToken) {
        int limit = ServerlessSupport.pageSize(maxResults);
        List<ScheduledAction> matching = live(region).stream()
                .filter(action -> namespaceName == null || namespaceName.equals(action.getNamespaceName()))
                .sorted(Comparator.comparing(ScheduledAction::getScheduledActionName))
                .map(ScheduledAction::new)
                .toList();
        return Pagination.paginate(matching, ScheduledAction::getScheduledActionName,
                limit, nextToken, "scheduled-action",
                token -> ServerlessSupport.invalidPagination("Invalid nextToken."));
    }

    /**
     * Applies the members {@code changes} carries. The schedule and the target replace the stored
     * ones as a whole, because each is a union of which exactly one member may be set. A new schedule,
     * or enabling a disabled action, restarts the count from now so nothing earlier is replayed.
     */
    public ScheduledAction updateScheduledAction(ScheduledAction changes, String region) {
        return mutate(region, changes.getScheduledActionName(), () -> {
            ScheduledAction current = require(changes.getScheduledActionName(), region);
            ScheduledAction updated = new ScheduledAction(current);
            if (changes.getRoleArn() != null) {
                updated.setRoleArn(changes.getRoleArn());
            }
            if (changes.getDescription() != null) {
                updated.setDescription(changes.getDescription());
            }
            if (changes.getEnabled() != null) {
                if (changes.getEnabled() && Boolean.FALSE.equals(current.getEnabled())) {
                    updated.setActivatedAt(clock.instant());
                }
                updated.setEnabled(changes.getEnabled());
            }
            if (changes.getStartTime() != null) {
                updated.setStartTime(changes.getStartTime());
            }
            if (changes.getEndTime() != null) {
                updated.setEndTime(changes.getEndTime());
            }
            if (changes.isScheduleSpecified()) {
                validateSchedule(changes);
                updated.setScheduleAt(changes.getScheduleAt());
                updated.setScheduleCron(changes.getScheduleCron());
                updated.setActivatedAt(clock.instant());
                updated.setLastClaimedInvocation(null);
            }
            boolean changesTarget = changes.isTargetSpecified()
                    || changes.getTargetNamespaceName() != null || changes.getSnapshotNamePrefix() != null
                    || changes.getRetentionPeriod() != null || changes.getSnapshotTags() != null;
            if (changesTarget) {
                validateTarget(changes, region);
                updated.setTargetNamespaceName(changes.getTargetNamespaceName());
                updated.setSnapshotNamePrefix(changes.getSnapshotNamePrefix());
                updated.setRetentionPeriod(changes.getRetentionPeriod());
                updated.setSnapshotTags(changes.getSnapshotTags());
            }
            validateWindow(updated);
            actions.put(storageKey(region, updated.getScheduledActionName()), updated);
            return new ScheduledAction(updated);
        });
    }

    public ScheduledAction deleteScheduledAction(String scheduledActionName, String region) {
        return mutate(region, scheduledActionName, () -> {
            ScheduledAction existing = new ScheduledAction(require(scheduledActionName, region));
            actions.delete(storageKey(region, scheduledActionName));
            return existing;
        });
    }

    /**
     * Takes the snapshot of every action whose latest due time falls in {@code (checkpoint, now]}. One
     * action's failure never stops the others. A deterministic entry point: the lifecycle scheduler
     * supplies the clock and tests supply a fixed instant.
     */
    public void runScheduledActions(Instant now) {
        for (AccountEntry<ScheduledAction> entry : actions.scanAllAccountEntries(key -> true)) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            String region = regionOf(entry.key());
            if (region == null) {
                continue;
            }
            ReentrantLock lock = lockOf(entry.accountId(), entry.key());
            lock.lock();
            try {
                RequestScopes.runAs(entry.accountId(), region, () -> runScheduledAction(entry, region, now));
            } catch (RuntimeException e) {
                LOG.warnv(e, "Scheduled action {0} in account {1} could not be processed",
                        entry.key(), entry.accountId());
            } finally {
                lock.unlock();
            }
        }
    }

    /** Runs under the action's lock, with the action's account and Region as the request scope. */
    private void runScheduledAction(AccountEntry<ScheduledAction> listed, String region, Instant now) {
        String accountId = listed.accountId();
        String key = listed.key();
        ScheduledAction action = actions.getForAccount(accountId, key).orElse(null);
        if (action == null) {
            return;
        }
        if (!isOnSameNamespace(action, region)) {
            actions.deleteForAccount(accountId, key);
            return;
        }
        if (action.getActivatedAt() == null) {
            // A stored action from before scheduling existed starts counting now, without replaying history.
            ScheduledAction activated = new ScheduledAction(action);
            activated.setActivatedAt(now);
            actions.putAllAtomicallyForAccount(accountId, Map.of(key, activated));
            return;
        }
        Instant checkpoint = action.getLastClaimedInvocation() != null
                && action.getLastClaimedInvocation().isAfter(action.getActivatedAt())
                ? action.getLastClaimedInvocation() : action.getActivatedAt();
        Optional<Instant> due = RedshiftServerlessSchedule.latestDue(action, checkpoint, now);
        if (due.isEmpty()) {
            return;
        }
        Instant scheduled = due.get();
        ScheduledAction outcome = new ScheduledAction(action);
        outcome.setLastClaimedInvocation(scheduled);
        outcome.setLastFailure(null);
        try {
            takeSnapshot(action, accountId, region, scheduled);
            outcome.setLastCompletedInvocation(scheduled);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Scheduled action {0} in account {1} failed at {2}",
                    action.getScheduledActionName(), accountId, scheduled);
            outcome.setLastFailure(String.valueOf(e.getMessage()));
        }
        recordOutcome(accountId, key, action.getScheduledActionUuid(), outcome);
    }

    private void takeSnapshot(ScheduledAction action, String accountId, String region, Instant scheduled) {
        Namespace target = serverless.findNamespace(action.getTargetNamespaceName(), region).orElseThrow(() ->
                ServerlessSupport.notFound("The namespace " + action.getTargetNamespaceName() + " was not found."));
        Optional<String> refusal = authorizer.refusal(action.getRoleArn(), accountId, target.getNamespaceArn());
        if (refusal.isPresent()) {
            throw ServerlessSupport.validation(refusal.get());
        }
        serverless.createSnapshot(snapshotName(action.getSnapshotNamePrefix(), scheduled),
                action.getTargetNamespaceName(), action.getRetentionPeriod(), action.getSnapshotTags(), region);
    }

    /** Stores the checkpoint and outcome only for the action that was run, not for a recreated one. */
    private void recordOutcome(String accountId, String key, String uuid, ScheduledAction outcome) {
        try {
            ScheduledAction current = actions.getForAccount(accountId, key).orElse(null);
            if (current != null && Objects.equals(current.getScheduledActionUuid(), uuid)) {
                ScheduledAction recorded = new ScheduledAction(current);
                recorded.setLastClaimedInvocation(outcome.getLastClaimedInvocation());
                recorded.setLastCompletedInvocation(outcome.getLastCompletedInvocation());
                recorded.setLastFailure(outcome.getLastFailure());
                actions.putAllAtomicallyForAccount(accountId, Map.of(key, recorded));
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "The outcome of scheduled action {0} in account {1} could not be stored", key, accountId);
        }
    }

    static String snapshotName(String prefix, Instant scheduled) {
        String suffix = "-" + INVOCATION_SUFFIX.format(scheduled);
        String base = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        if (base.length() + suffix.length() > MAX_SNAPSHOT_NAME_LENGTH) {
            base = base.substring(0, MAX_SNAPSHOT_NAME_LENGTH - suffix.length());
        }
        return base + suffix;
    }

    @Override
    public void clear() {
        actions.clear();
        actionLocks.clear();
    }

    private <T> T mutate(String region, String name, Supplier<T> body) {
        ReentrantLock lock = lockOf(regionResolver.getAccountId(), storageKey(region, name));
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

    private static String regionOf(String key) {
        int separator = key.indexOf("::");
        return separator < 0 ? null : key.substring(0, separator);
    }

    private ScheduledAction require(String scheduledActionName, String region) {
        ScheduledAction action = actions.get(storageKey(region, scheduledActionName)).orElse(null);
        if (action != null && !isOnSameNamespace(action, region)) {
            actions.delete(storageKey(region, scheduledActionName));
            action = null;
        }
        if (action == null) {
            throw ServerlessSupport.notFound("The scheduled action " + scheduledActionName + " was not found.");
        }
        return action;
    }

    /** The region's actions whose namespace is still there; any other is deleted as it is found. */
    private List<ScheduledAction> live(String region) {
        return ServerlessSupport.live(actions, region,
                action -> isOnSameNamespace(action, region),
                action -> storageKey(region, action.getScheduledActionName()));
    }

    /** A namespace recreated under the same name has a new id, so it never inherits its predecessor's actions. */
    private boolean isOnSameNamespace(ScheduledAction action, String region) {
        return serverless.findNamespace(action.getNamespaceName(), region)
                .map(namespace -> Objects.equals(namespace.getNamespaceId(), action.getOwnerId()))
                .orElse(false);
    }

    private String namespaceIdOf(String namespaceName, String region) {
        return serverless.findNamespace(namespaceName, region).map(Namespace::getNamespaceId).orElse(null);
    }

    private void requireNamespace(String namespaceName, String region) {
        if (!serverless.namespaceExists(namespaceName, region)) {
            throw ServerlessSupport.notFound("The namespace " + namespaceName + " was not found.");
        }
    }

    /** Exactly one of {@code at} and {@code cron} must be set: the schedule is a union. */
    private static void validateSchedule(ScheduledAction action) {
        boolean hasAt = action.getScheduleAt() != null;
        boolean hasCron = action.getScheduleCron() != null && !action.getScheduleCron().isBlank();
        if (hasAt == hasCron) {
            throw ServerlessSupport.validation("schedule must set exactly one of at and cron.");
        }
        RedshiftServerlessSchedule.validate(action);
    }

    private void validateTarget(ScheduledAction action, String region) {
        if (action.getTargetNamespaceName() == null) {
            throw ServerlessSupport.validation("targetAction must set createSnapshot.");
        }
        requireNamespace(action.getTargetNamespaceName(), region);
        if (action.getSnapshotNamePrefix() != null) {
            RedshiftServerlessService.validateSnapshotName(snapshotName(action.getSnapshotNamePrefix(), Instant.EPOCH));
        }
        Integer retention = action.getRetentionPeriod();
        if (retention != null && retention != -1 && (retention < 1 || retention > MAX_RETENTION_DAYS)) {
            throw ServerlessSupport.validation("targetAction.createSnapshot.retentionPeriod must be -1 "
                    + "(indefinite) or between 1 and " + MAX_RETENTION_DAYS + ".");
        }
    }

    private static void validateWindow(ScheduledAction action) {
        if (action.getStartTime() != null && action.getEndTime() != null
                && !action.getEndTime().isAfter(action.getStartTime())) {
            throw ServerlessSupport.validation("endTime must be after startTime.");
        }
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }
}
