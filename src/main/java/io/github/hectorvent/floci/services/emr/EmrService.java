package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emr.model.EmrBlockPublicAccess;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceFleet;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceGroup;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.emr.model.SecurityConfiguration;
import io.github.hectorvent.floci.services.emrserverless.EmrServerlessSparkRunner;
import io.github.hectorvent.floci.services.emrserverless.SparkJobRunner;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * EMR management-plane business logic. Clusters and steps go through the states of
 * {@link EmrClusterLifecycle}, timed by {@code cluster-startup-delay-seconds},
 * {@code step-duration-seconds} and {@code cluster-termination-delay-seconds}, all 0 by default, which
 * makes a cluster ready, and a step complete, before the call that started it returns. A call brings
 * each cluster it reads up to the time it is made, and the service wakes at each cluster's next
 * change, so the change's event goes out without anyone asking. Events are published once the call
 * that made the change no longer holds the service.
 *
 * <p>In the default {@code immediate} runner mode nothing runs. In the {@code docker} one each step
 * runs, in a container of its own, from when it goes RUNNING until its Spark driver exits, on a thread
 * of its own that ends the step under the service's lock: COMPLETED on exit code 0, FAILED otherwise,
 * with its ActionOnFailure taken.
 */
@ApplicationScoped
public class EmrService implements Resettable {

    private static final Logger LOG = Logger.getLogger(EmrService.class);
    private static final String UPPER_ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    // AWS's answer to a change on a terminated cluster, for AddJobFlowSteps and ModifyInstanceFleet; Floci
    // gives it to AddInstanceGroups and AddInstanceFleet too, for one shutting down.
    private static final String JOB_FLOW_SHUT_DOWN =
            "A job flow that is shutting down, terminated, or finished may not be modified.";
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String RUNNER_DOCKER = "docker";
    // The service a step's driver belongs to, which names it and labels it.
    private static final String SERVICE = "emr";

    private final AccountAwareStorageBackend<EmrCluster> clusterStore;
    private final StorageBackend<String, SecurityConfiguration> secConfigStore;
    // Block public access is an account setting per Region, so this store is keyed by Region.
    private final StorageBackend<String, EmrBlockPublicAccess> blockPublicAccessStore;
    private final RegionResolver regionResolver;
    private final EmrEventPublisher eventPublisher;
    private final EmrClusterLifecycle lifecycle;
    private final Clock clock;
    private final Wakeups wakeups;
    // Each cluster's next wakeup, by account and id, so a call that reads a cluster does not add another.
    private final Map<String, Instant> wakeupsDue = new ConcurrentHashMap<>();
    // The docker runner mode's; null in the immediate one.
    private final SparkJobRunner sparkRunner;
    private final Executor stepExecutor;
    private final IamService iamService;
    // Where a step's driver runs: image-template, with the release label for %s, and docker-network; null when
    // not set.
    private final String imageTemplate;
    private final String dockerNetwork;
    // The steps a thread is running, by account and id; a RUNNING container step not here was left by a restart.
    private final Set<String> executing = new HashSet<>();
    // Raised by each reset, so a step's thread from before it writes nothing back after the wipe.
    private long generation;

    private static final Set<String> COMPUTE_LIMITS_UNIT_TYPES = Set.of("InstanceFleetUnits", "Instances", "VCPU");
    private static final Set<String> SCALING_STRATEGIES = Set.of("DEFAULT", "ADVANCED");
    private static final long MIN_IDLE_TIMEOUT_SECONDS = 60;
    private static final long MAX_IDLE_TIMEOUT_SECONDS = 604_800;
    private static final int MAX_PORT = 65_535;
    // The AWS default: block public access enabled in every Region, with port 22 as an exception.
    private static final String DEFAULT_BLOCK_PUBLIC_ACCESS =
            "{\"BlockPublicSecurityGroupRules\":true,"
                    + "\"PermittedPublicSecurityGroupRuleRanges\":[{\"MinRange\":22,\"MaxRange\":22}]}";
    private final String defaultReleaseLabel;

    /**
     * When the service next brings a cluster up to date by itself, and where it then sends the events
     * that queued; a test runs both when it chooses.
     */
    interface Wakeups {

        void at(Instant due, Runnable task);

        /** Sends a wakeup's events, by default on the wakeup's own thread. */
        default void deliver(Runnable delivery) {
            delivery.run();
        }

        default void close() {
        }

        /**
         * Wakeups on a daemon thread, each at its time by {@code clock}, and their events sent on another,
         * in the order they queued: a rule's target can take seconds to answer, and no cluster's change
         * waits for it.
         */
        static Wakeups timer(Clock clock) {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("emr-lifecycle").factory());
            ExecutorService deliveries = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("emr-events").factory());
            return new Wakeups() {
                @Override
                public void at(Instant due, Runnable task) {
                    long delay = Math.max(0, Duration.between(clock.instant(), due).toMillis());
                    executor.schedule(task, delay, TimeUnit.MILLISECONDS);
                }

                @Override
                public void deliver(Runnable delivery) {
                    deliveries.execute(delivery);
                }

                @Override
                public void close() {
                    executor.shutdownNow();
                    deliveries.shutdownNow();
                }
            };
        }
    }

    @Inject
    public EmrService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                      EmrEventPublisher eventPublisher, EmrServerlessSparkRunner sparkRunner, IamService iamService) {
        this(storageFactory, config, regionResolver, eventPublisher, Clock.systemUTC(),
                Wakeups.timer(Clock.systemUTC()),
                RUNNER_DOCKER.equalsIgnoreCase(config.services().emr().runnerMode()) ? sparkRunner : null,
                Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("emr-step-", 0).factory()),
                iamService);
    }

    EmrService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
               EmrEventPublisher eventPublisher, Clock clock, Wakeups wakeups) {
        this(storageFactory, config, regionResolver, eventPublisher, clock, wakeups, null, Runnable::run, null);
    }

    EmrService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
               EmrEventPublisher eventPublisher, Clock clock, Wakeups wakeups, SparkJobRunner sparkRunner,
               Executor stepExecutor, IamService iamService) {
        this.clusterStore = storageFactory.create("emr", "emr-clusters.json",
                new TypeReference<Map<String, EmrCluster>>() {});
        this.secConfigStore = storageFactory.create("emr", "emr-security-configs.json",
                new TypeReference<Map<String, SecurityConfiguration>>() {});
        this.blockPublicAccessStore = storageFactory.create("emr", "emr-block-public-access.json",
                new TypeReference<Map<String, EmrBlockPublicAccess>>() {});
        this.regionResolver = regionResolver;
        this.eventPublisher = eventPublisher;
        this.defaultReleaseLabel = config.services().emr().defaultReleaseLabel();
        this.sparkRunner = sparkRunner;
        this.stepExecutor = stepExecutor;
        this.iamService = iamService;
        this.imageTemplate = config.services().emr().imageTemplate().orElse(null);
        this.dockerNetwork = config.services().emr().dockerNetwork().orElse(null);
        this.lifecycle = new EmrClusterLifecycle(config.services().emr().clusterStartupDelaySeconds(),
                config.services().emr().stepDurationSeconds(),
                config.services().emr().clusterTerminationDelaySeconds(), eventPublisher,
                sparkRunner != null ? new ContainerSteps() : null);
        this.clock = clock;
        this.wakeups = wakeups;
        // Before any step of this process can start, the next step a restarted cluster's wakeup below starts
        // included: a driver an earlier process left belongs to a step read as FAILED, and the sweep removes
        // every driver of this deployment it finds.
        if (sparkRunner != null && config.services().emr().reconcileContainersOnStartup()) {
            sparkRunner.removeLeftoverDrivers(SERVICE);
        }
        // After a restart, a stored cluster with a change still to come wakes the service as before, and one
        // with a step a container ran, which no thread runs any more, wakes it at once to fail that step.
        for (AccountAwareStorageBackend.AccountEntry<EmrCluster> entry
                : clusterStore.scanAllAccountEntries(k -> true)) {
            Instant due = sparkRunner != null && hasContainerStepRunning(entry.value())
                    ? clock.instant() : lifecycle.nextChange(entry.value());
            if (due != null) {
                wakeAt(entry.accountId(), entry.value(), due);
            }
        }
    }

    // A normal-scoped bean is built on first use, so without this a cluster left mid-change by a restart
    // would not move on, nor send its events, until the next EMR call.
    void onStart(@Observes StartupEvent ignored) {
        LOG.debugv("EMR service ready");
    }

    @PreDestroy
    void shutdown() {
        wakeups.close();
        if (stepExecutor instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
    }

    /**
     * A reset has begun: the steps' threads from before it write nothing more, so the storage wipe that
     * follows leaves none of their clusters behind. Its teardown has already stopped their drivers.
     */
    @Override
    public synchronized void beforeReset() {
        generation++;
        executing.clear();
    }

    @Override
    public void clear() {
        // The clusters live in storage, which the reset has already wiped.
    }

    // ──────────────────────────── Cluster lifecycle ────────────────────────────

    public EmrCluster runJobFlow(EmrCluster cluster, String region) {
        return call(() -> {
            // Only a missing Name fails AWS's framework validation: the member is REQUIRED but its
            // constraint is len=[0..256], so an explicit empty string is accepted by real EMR.
            if (cluster.getName() == null) {
                throw SmithyValidationErrors.validationException(SmithyValidationErrors.notNull("name"));
            }
            String id = "j-" + randomId(13);
            cluster.setId(id);
            cluster.setRegion(region);
            cluster.setClusterArn(regionResolver.buildArn("elasticmapreduce", region, "cluster/" + id));
            if (cluster.getReleaseLabel() == null) {
                cluster.setReleaseLabel(defaultReleaseLabel);
            }
            cluster.setInstanceCollectionType(cluster.getInstanceFleets().isEmpty()
                    ? "INSTANCE_GROUP" : "INSTANCE_FLEET");
            cluster.setAutoTerminate(!cluster.isKeepJobFlowAliveWhenNoSteps());
            cluster.setMasterPublicDnsName(masterDnsName(region));
            for (EmrInstanceGroup g : cluster.getInstanceGroups()) {
                g.setId("ig-" + randomId(13));
                g.setRunningInstanceCount(g.getRequestedInstanceCount());
                g.setState("RUNNING");
            }
            for (EmrInstanceFleet f : cluster.getInstanceFleets()) {
                f.setId("if-" + randomId(13));
                f.setProvisionedOnDemandCapacity(f.getTargetOnDemandCapacity());
                f.setProvisionedSpotCapacity(f.getTargetSpotCapacity());
                f.setState("RUNNING");
            }
            cluster.getSteps().forEach(step -> step.setId("s-" + randomId(13)));
            lifecycle.start(cluster, now(), region);
            store(cluster);
            return cluster;
        });
    }

    /** The master node's private DNS name, derived from the region so a stored name never goes stale. */
    static String masterDnsName(String region) {
        return AwsRegions.ec2PrivateIpDnsName("10.0.0.1", region);
    }

    public EmrCluster describeCluster(String id) {
        return call(() -> snapshotOf(requireCluster(id)));
    }

    public List<EmrCluster> listClusters(String region, List<String> states,
                                          Instant createdAfter, Instant createdBefore) {
        return call(() -> {
            List<EmrCluster> result = new ArrayList<>();
            for (EmrCluster c : clusterStore.scan(k -> true)) {
                if (!region.equals(c.getRegion())) {
                    continue;
                }
                settle(c);
                if (states != null && !states.isEmpty() && !states.contains(c.getState())) {
                    continue;
                }
                Instant createdAt = c.getCreationDateTime();
                if (createdAt != null) {
                    createdAt = createdAt.truncatedTo(ChronoUnit.MILLIS);
                }
                if (createdAfter != null && (createdAt == null || !createdAt.isAfter(createdAfter))) {
                    continue;
                }
                if (createdBefore != null && (createdAt == null || !createdAt.isBefore(createdBefore))) {
                    continue;
                }
                result.add(snapshotOf(c));
            }
            return result;
        });
    }

    public void terminateJobFlows(List<String> ids) {
        // A request naming a protected cluster still terminates the others before it fails.
        run(() -> {
            // AWS terminates the unprotected clusters in the request and then fails it with a
            // ValidationException if any were termination protected.
            boolean anyProtected = false;
            for (String id : ids) {
                EmrCluster cluster = findCluster(id).orElse(null);
                if (cluster == null || isShutDown(cluster)) {
                    continue;
                }
                if (cluster.isTerminationProtected()) {
                    anyProtected = true;
                    continue;
                }
                lifecycle.terminate(cluster, "USER_REQUEST", "Terminated by user request", now(), regionOf(cluster));
                store(cluster);
            }
            if (anyProtected) {
                throw new AwsException("ValidationException",
                        "Could not shut down one or more job flows since they are termination protected.", 400);
            }
        });
    }

    public void setTerminationProtection(List<String> ids, boolean value) {
        run(() -> mutateClusters(ids, c -> c.setTerminationProtected(value)));
    }

    public void setVisibleToAllUsers(List<String> ids, boolean value) {
        run(() -> mutateClusters(ids, c -> c.setVisibleToAllUsers(value)));
    }

    public void setKeepJobFlowAliveWhenNoSteps(List<String> ids, boolean value) {
        run(() -> mutateClusters(ids, c -> {
            c.setKeepJobFlowAliveWhenNoSteps(value);
            c.setAutoTerminate(!value);
        }));
    }

    public void setUnhealthyNodeReplacement(List<String> ids, boolean value) {
        run(() -> mutateClusters(ids, c -> c.setUnhealthyNodeReplacement(value)));
    }

    public int modifyCluster(String id, Integer stepConcurrencyLevel) {
        return call(() -> {
            EmrCluster cluster = requireCluster(id);
            if (stepConcurrencyLevel != null) {
                cluster.setStepConcurrencyLevel(stepConcurrencyLevel);
                lifecycle.concurrencyChanged(cluster, now(), regionOf(cluster));
                store(cluster);
            }
            return cluster.getStepConcurrencyLevel();
        });
    }

    // ──────────────────────────── Steps ────────────────────────────

    public List<String> addJobFlowSteps(String clusterId, List<EmrStep> steps) {
        return call(() -> {
            // AWS's own wording for an unknown job flow here, not DescribeCluster's: a botocore ClientError
            // (raster-foundry/raster-foundry#352) and a CloudTrail record on AWS re:Post both show it.
            EmrCluster cluster = clusterId == null ? requireCluster(null) : findCluster(clusterId).orElseThrow(
                    () -> new AwsException("ValidationException", "Specified job flow ID does not exist.", 400));
            if (isShutDown(cluster)) {
                throw jobFlowShutDownValidation();
            }
            steps.forEach(step -> step.setId("s-" + randomId(13)));
            cluster.getSteps().addAll(steps);
            lifecycle.submit(cluster, steps, now(), regionOf(cluster));
            store(cluster);
            return steps.stream().map(EmrStep::getId).toList();
        });
    }

    public EmrStep describeStep(String clusterId, String stepId) {
        return call(() -> {
            EmrCluster cluster = snapshotOf(requireCluster(clusterId));
            return cluster.getSteps().stream()
                    .filter(s -> s.getId().equals(stepId))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("InvalidRequestException",
                            "Step " + stepId + " does not exist.", 400));
        });
    }

    public List<EmrStep> listSteps(String clusterId, List<String> states, List<String> stepIds) {
        return call(() -> {
            EmrCluster cluster = snapshotOf(requireCluster(clusterId));
            List<EmrStep> result = new ArrayList<>();
            // AWS returns steps in reverse submission order (newest first).
            List<EmrStep> steps = cluster.getSteps();
            for (int i = steps.size() - 1; i >= 0; i--) {
                EmrStep step = steps.get(i);
                if (states != null && !states.isEmpty() && !states.contains(step.getState())) {
                    continue;
                }
                if (stepIds != null && !stepIds.isEmpty() && !stepIds.contains(step.getId())) {
                    continue;
                }
                result.add(step);
            }
            return result;
        });
    }

    public record CancelStepInfo(String stepId, String status, String reason) {}

    public List<CancelStepInfo> cancelSteps(String clusterId, List<String> stepIds) {
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            List<CancelStepInfo> result = new ArrayList<>();
            for (String stepId : stepIds) {
                EmrStep step = cluster.getSteps().stream()
                        .filter(s -> s.getId().equals(stepId)).findFirst().orElse(null);
                String refusal = step == null ? "Step does not exist."
                        : lifecycle.cancel(cluster, step, now(), regionOf(cluster));
                result.add(refusal == null
                        ? new CancelStepInfo(stepId, "SUBMITTED", null)
                        : new CancelStepInfo(stepId, "FAILED", refusal));
            }
            store(cluster);
            return result;
        });
    }

    // ──────────────────────────── Instance groups / fleets ────────────────────────────

    public List<String> addInstanceGroups(String clusterId, List<EmrInstanceGroup> groups) {
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDownValidation);
            List<String> ids = new ArrayList<>();
            for (EmrInstanceGroup group : groups) {
                group.setId("ig-" + randomId(13));
                group.setRunningInstanceCount(group.getRequestedInstanceCount());
                group.setState("RUNNING");
                cluster.getInstanceGroups().add(group);
                ids.add(group.getId());
            }
            clusterStore.put(clusterId, cluster);
            return ids;
        });
    }

    /** One InstanceGroupModifyConfig: a null count or configurations leaves that setting as it is. */
    public record InstanceGroupModification(String instanceGroupId, Integer instanceCount, String configurations) {}

    /**
     * Resizes or reconfigures instance groups in place. ClusterId is optional in the request, so
     * without it each group is looked up across the account's clusters in the Region. Every modification is
     * checked before any is applied.
     */
    public void modifyInstanceGroups(String clusterId, String region, List<InstanceGroupModification> modifications) {
        run(() -> {
            if (modifications.isEmpty()) {
                throw invalid("InstanceGroups must contain at least one instance group.");
            }
            Map<String, EmrCluster> touched = new LinkedHashMap<>();
            List<EmrInstanceGroup> groups = new ArrayList<>();
            for (InstanceGroupModification mod : modifications) {
                EmrCluster found = clusterId != null
                        ? requireCluster(clusterId) : clusterOwning(region, mod.instanceGroupId());
                // AWS's answer here, recorded by Terraform users
                // (hashicorp/terraform-provider-aws#1355 and #9400).
                if (!runsOrWaits(found)) {
                    throw new AwsException("ValidationException",
                            "An instance group may only be modified when the cluster is running or waiting.", 400);
                }
                // One copy per cluster, so two groups of the same cluster are changed and stored together.
                EmrCluster cluster = touched.computeIfAbsent(found.getId(), id -> found);
                EmrInstanceGroup group = requireInstanceGroup(cluster, mod.instanceGroupId());
                if (mod.instanceCount() != null) {
                    if (mod.instanceCount() < 0) {
                        throw invalid("InstanceCount must not be negative.");
                    }
                    if ("MASTER".equals(group.getInstanceGroupType())
                            && mod.instanceCount() != group.getRequestedInstanceCount()) {
                        throw invalid("The instance count of a master instance group cannot be modified.");
                    }
                }
                groups.add(group);
            }
            for (int i = 0; i < modifications.size(); i++) {
                InstanceGroupModification mod = modifications.get(i);
                EmrInstanceGroup group = groups.get(i);
                if (mod.instanceCount() != null) {
                    group.setRequestedInstanceCount(mod.instanceCount());
                    group.setRunningInstanceCount(mod.instanceCount());
                }
                if (mod.configurations() != null) {
                    group.setConfigurations(mod.configurations());
                    group.setConfigurationsVersion(group.getConfigurationsVersion() + 1);
                }
            }
            touched.forEach(clusterStore::put);
        });
    }

    public List<EmrInstanceGroup> listInstanceGroups(String clusterId) {
        return call(() -> snapshotOf(requireCluster(clusterId)).getInstanceGroups());
    }

    public String addInstanceFleet(String clusterId, EmrInstanceFleet fleet) {
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDown);
            fleet.setId("if-" + randomId(13));
            fleet.setProvisionedOnDemandCapacity(fleet.getTargetOnDemandCapacity());
            fleet.setProvisionedSpotCapacity(fleet.getTargetSpotCapacity());
            fleet.setState("RUNNING");
            cluster.getInstanceFleets().add(fleet);
            clusterStore.put(clusterId, cluster);
            return fleet.getId();
        });
    }

    /** Resizes an instance fleet; a null target leaves that capacity as it is. */
    public void modifyInstanceFleet(String clusterId, String instanceFleetId,
                                    Integer targetOnDemandCapacity, Integer targetSpotCapacity) {
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            if (instanceFleetId == null) {
                throw invalid("InstanceFleetId is required.");
            }
            // AWS's answer for a cluster terminated a while before, ErrorCode member included
            // (hashicorp/terraform-provider-aws#31375, 2023); its wording covers one shutting down.
            if (isShutDown(cluster)) {
                throw jobFlowShutDown();
            }
            // And for one in any other state but running or waiting, a STARTING or BOOTSTRAPPING one
            // included, ErrorCode member too (hashicorp/terraform-provider-aws#15548, 2020).
            if (!runsOrWaits(cluster)) {
                throw new AwsException("InvalidRequestException",
                        "An instance fleet may only be modified when the cluster is running or waiting.", 400,
                        Map.of("ErrorCode", "MODIFY_INSTANCE_FLEET_OF_RUNNING_JOBFLOW_ONLY"));
            }
            EmrInstanceFleet fleet = cluster.getInstanceFleets().stream()
                    .filter(f -> instanceFleetId.equals(f.getId()))
                    .findFirst()
                    .orElseThrow(() -> invalid("Instance fleet " + instanceFleetId + " is not in cluster "
                            + clusterId + "."));
            int onDemand = targetOnDemandCapacity != null ? targetOnDemandCapacity : fleet.getTargetOnDemandCapacity();
            int spot = targetSpotCapacity != null ? targetSpotCapacity : fleet.getTargetSpotCapacity();
            if (onDemand < 0 || spot < 0) {
                throw invalid("Target capacities must not be negative.");
            }
            if ("MASTER".equals(fleet.getInstanceFleetType())
                    && (onDemand != fleet.getTargetOnDemandCapacity() || spot != fleet.getTargetSpotCapacity())) {
                throw invalid("The target capacity of a master instance fleet cannot be modified.");
            }
            fleet.setTargetOnDemandCapacity(onDemand);
            fleet.setTargetSpotCapacity(spot);
            fleet.setProvisionedOnDemandCapacity(onDemand);
            fleet.setProvisionedSpotCapacity(spot);
            clusterStore.put(clusterId, cluster);
        });
    }

    /** The cluster's bootstrap actions as given to RunJobFlow, raw JSON; null when it had none. */
    public String listBootstrapActions(String clusterId) {
        return call(() -> requireCluster(clusterId).getBootstrapActions());
    }

    public List<EmrInstanceFleet> listInstanceFleets(String clusterId) {
        return call(() -> snapshotOf(requireCluster(clusterId)).getInstanceFleets());
    }

    public EmrCluster getClusterForInstances(String clusterId) {
        return call(() -> snapshotOf(requireCluster(clusterId)));
    }

    // ──────────────────────────── Security configurations ────────────────────────────

    public SecurityConfiguration createSecurityConfiguration(String name, String json) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidRequestException", "Name is required.", 400);
        }
        if (secConfigStore.get(name).isPresent()) {
            throw new AwsException("InvalidRequestException",
                    "Security configuration already exists: " + name, 400);
        }
        SecurityConfiguration sc = new SecurityConfiguration();
        sc.setName(name);
        sc.setSecurityConfiguration(json);
        sc.setCreationDateTime(Instant.now());
        secConfigStore.put(name, sc);
        return sc;
    }

    public SecurityConfiguration describeSecurityConfiguration(String name) {
        return secConfigStore.get(name).orElseThrow(() -> new AwsException(
                "InvalidRequestException", "Security configuration does not exist: " + name, 400));
    }

    public void deleteSecurityConfiguration(String name) {
        if (secConfigStore.get(name).isEmpty()) {
            throw new AwsException("InvalidRequestException",
                    "Security configuration does not exist: " + name, 400);
        }
        secConfigStore.delete(name);
    }

    // ──────────────────────────── Release catalogue ────────────────────────────

    private static final int MAX_RESULTS = 100;
    private static final int INSTANCE_TYPE_PAGE = 50;

    /**
     * The catalogue's releases, newest first. Floci's configured default release label is always
     * among them, with no applications when the catalogue does not describe it, so a non-default
     * setting never makes Floci's own default unknown.
     */
    List<EmrReleaseCatalog.Release> releases() {
        if (EmrReleaseCatalog.release(defaultReleaseLabel).isPresent()) {
            return EmrReleaseCatalog.RELEASES;
        }
        List<EmrReleaseCatalog.Release> all = new ArrayList<>(EmrReleaseCatalog.RELEASES);
        all.add(new EmrReleaseCatalog.Release(defaultReleaseLabel, List.of(), List.of()));
        all.sort((a, b) -> EmrReleaseCatalog.NEWEST_FIRST.compare(a.label(), b.label()));
        return all;
    }

    /**
     * Release labels, newest first, narrowed by {@code prefix} (such as {@code emr-6}) and by
     * {@code application}, a name or {@code name@version} (such as {@code spark@3.5.2}) matched
     * without regard to case. A token belongs to the filters it was issued for, so one replayed
     * with other filters, or altered, is refused.
     */
    public PaginatedResult<String> listReleaseLabels(String prefix, String application, String nextToken,
                                                     Integer maxResults) {
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_RESULTS)) {
            throw new AwsException("InvalidRequestException",
                    "MaxResults must be between 1 and " + MAX_RESULTS + ".", 400);
        }
        String appName = null;
        String appVersion = null;
        if (application != null && !application.isBlank()) {
            int at = application.indexOf('@');
            appName = (at < 0 ? application : application.substring(0, at)).trim();
            appVersion = at < 0 ? null : application.substring(at + 1).trim();
        }
        String name = appName;
        String version = appVersion;
        List<String> labels = releases().stream()
                .filter(r -> prefix == null || prefix.isBlank() || r.label().startsWith(prefix))
                .filter(r -> name == null || r.applications().stream().anyMatch(a -> a.name().equalsIgnoreCase(name)
                        && (version == null || version.isEmpty() || a.version().equalsIgnoreCase(version))))
                .map(EmrReleaseCatalog.Release::label)
                .toList();
        String namespace = "ListReleaseLabels|" + Objects.toString(prefix, "") + "|" + Objects.toString(application, "");
        return Pagination.paginate(labels, positionIn(labels), maxResults != null ? maxResults : MAX_RESULTS,
                nextToken, namespace, EmrService::invalidToken);
    }

    /** A release's applications and OS releases; no label means the latest release. */
    public EmrReleaseCatalog.Release describeReleaseLabel(String label) {
        return label == null || label.isBlank() ? releases().get(0) : requireRelease(label);
    }

    public PaginatedResult<EmrReleaseCatalog.InstanceType> listSupportedInstanceTypes(String label, String marker) {
        if (label == null || label.isBlank()) {
            throw new AwsException("InvalidRequestException", "ReleaseLabel is required.", 400);
        }
        requireRelease(label);
        List<EmrReleaseCatalog.InstanceType> types = EmrReleaseCatalog.INSTANCE_TYPES;
        return Pagination.paginate(types, positionIn(types), INSTANCE_TYPE_PAGE, marker,
                "ListSupportedInstanceTypes|" + label, EmrService::invalidToken);
    }

    private EmrReleaseCatalog.Release requireRelease(String label) {
        return releases().stream()
                .filter(r -> r.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "The specified release label " + label + " is not valid.", 400));
    }

    /**
     * A cursor that keeps the catalogue's own order: {@link Pagination} sorts by cursor, and the
     * catalogue is newest first rather than alphabetical.
     */
    private static <T> Function<T, String> positionIn(List<T> items) {
        Map<T, String> positions = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            positions.put(items.get(i), String.format("%06d", i));
        }
        return positions::get;
    }

    private static AwsException invalidToken(String token) {
        return new AwsException("InvalidRequestException", "Invalid pagination token: " + token, 400);
    }

    public List<SecurityConfiguration> listSecurityConfigurations() {
        return new ArrayList<>(secConfigStore.scan(k -> true));
    }

    // ──────────────────────────── Tags ────────────────────────────

    public void addTags(String resourceId, Map<String, String> tags) {
        run(() -> {
            EmrCluster cluster = requireCluster(resourceId);
            cluster.getTags().putAll(tags);
            clusterStore.put(resourceId, cluster);
        });
    }

    public void removeTags(String resourceId, List<String> keys) {
        run(() -> {
            EmrCluster cluster = requireCluster(resourceId);
            keys.forEach(cluster.getTags()::remove);
            clusterStore.put(resourceId, cluster);
        });
    }

    // ──────────────────────────── Helpers ────────────────────────────

    // ──────────────────────────── Scaling and termination policies ────────────────────────────

    public void putManagedScalingPolicy(String clusterId, JsonNode policy) {
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            validateManagedScalingPolicy(policy);
            cluster.setManagedScalingPolicy(policy.toString());
            clusterStore.put(clusterId, cluster);
        });
    }

    /** The cluster's managed scaling policy as raw JSON, or null when it has none. */
    public String getManagedScalingPolicy(String clusterId) {
        return call(() -> requireCluster(clusterId).getManagedScalingPolicy());
    }

    public void removeManagedScalingPolicy(String clusterId) {
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            cluster.setManagedScalingPolicy(null);
            clusterStore.put(clusterId, cluster);
        });
    }

    /** AutoTerminationPolicy is optional in the request; an absent one is stored as an empty policy. */
    public void putAutoTerminationPolicy(String clusterId, JsonNode policy) {
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            validateAutoTerminationPolicy(policy);
            cluster.setAutoTerminationPolicy(
                    policy == null || policy.isMissingNode() || policy.isNull() ? "{}" : policy.toString());
            clusterStore.put(clusterId, cluster);
        });
    }

    /** The cluster's auto termination policy as raw JSON, or null when it has none. */
    public String getAutoTerminationPolicy(String clusterId) {
        return call(() -> requireCluster(clusterId).getAutoTerminationPolicy());
    }

    public void removeAutoTerminationPolicy(String clusterId) {
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            cluster.setAutoTerminationPolicy(null);
            clusterStore.put(clusterId, cluster);
        });
    }

    /** Attaches an automatic scaling policy to a core or task instance group and returns the group. */
    public EmrInstanceGroup putAutoScalingPolicy(String clusterId, String instanceGroupId, JsonNode policy) {
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            EmrInstanceGroup group = requireInstanceGroup(cluster, instanceGroupId);
            validateAutoScalingPolicy(policy, group.getInstanceGroupType());
            group.setAutoScalingPolicy(policy.toString());
            clusterStore.put(clusterId, cluster);
            return group;
        });
    }

    public void removeAutoScalingPolicy(String clusterId, String instanceGroupId) {
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            requireInstanceGroup(cluster, instanceGroupId).setAutoScalingPolicy(null);
            clusterStore.put(clusterId, cluster);
        });
    }

    // ──────────────────────────── Block public access ────────────────────────────

    /**
     * The account's block public access configuration in {@code region}. Until one is put, it is the
     * AWS default: enabled for every Region, with port 22 as an exception. The default is stored the
     * first time it is read, so its metadata stays the same on later reads.
     */
    public synchronized EmrBlockPublicAccess getBlockPublicAccess(String region) {
        return blockPublicAccessStore.get(region).orElseGet(() -> {
            EmrBlockPublicAccess setting = new EmrBlockPublicAccess();
            setting.setConfiguration(DEFAULT_BLOCK_PUBLIC_ACCESS);
            setting.setCreationDateTime(Instant.now());
            setting.setCreatedByArn(regionResolver.buildGlobalArn("iam", "root"));
            blockPublicAccessStore.put(region, setting);
            return setting;
        });
    }

    public synchronized void putBlockPublicAccess(String region, JsonNode configuration) {
        validateBlockPublicAccess(configuration);
        EmrBlockPublicAccess setting = new EmrBlockPublicAccess();
        setting.setConfiguration(configuration.toString());
        setting.setCreationDateTime(Instant.now());
        setting.setCreatedByArn(regionResolver.buildGlobalArn("iam", "root"));
        blockPublicAccessStore.put(region, setting);
    }

    // ──────────────────────────── Policy validation (from the API model) ────────────────────────────

    /** ComputeLimits is required, with UnitType, MinimumCapacityUnits and MaximumCapacityUnits. */
    static void validateManagedScalingPolicy(JsonNode policy) {
        if (policy == null || !policy.isObject()) {
            throw invalid("ManagedScalingPolicy is required.");
        }
        JsonNode limits = policy.path("ComputeLimits");
        if (!limits.isObject()) {
            throw invalid("ManagedScalingPolicy.ComputeLimits is required.");
        }
        if (!COMPUTE_LIMITS_UNIT_TYPES.contains(limits.path("UnitType").asText(""))) {
            throw invalid("ComputeLimits.UnitType must be one of " + COMPUTE_LIMITS_UNIT_TYPES + ".");
        }
        if (!limits.path("MinimumCapacityUnits").isInt() || !limits.path("MaximumCapacityUnits").isInt()) {
            throw invalid("ComputeLimits.MinimumCapacityUnits and MaximumCapacityUnits are required.");
        }
        int min = limits.get("MinimumCapacityUnits").asInt();
        int max = limits.get("MaximumCapacityUnits").asInt();
        if (min < 0 || min > max) {
            throw invalid("ComputeLimits.MinimumCapacityUnits must be between 0 and MaximumCapacityUnits.");
        }
        for (String optional : List.of("MaximumOnDemandCapacityUnits", "MaximumCoreCapacityUnits")) {
            JsonNode value = limits.path(optional);
            if (!value.isMissingNode() && (!value.isInt() || value.asInt() < 0 || value.asInt() > max)) {
                throw invalid("ComputeLimits." + optional + " must be between 0 and MaximumCapacityUnits.");
            }
        }
        JsonNode strategy = policy.path("ScalingStrategy");
        if (!strategy.isMissingNode() && !SCALING_STRATEGIES.contains(strategy.asText(""))) {
            throw invalid("ScalingStrategy must be one of " + SCALING_STRATEGIES + ".");
        }
    }

    /** IdleTimeout, when given, is 60 to 604800 seconds. */
    static void validateAutoTerminationPolicy(JsonNode policy) {
        if (policy == null || policy.isMissingNode() || policy.isNull()) {
            return;
        }
        if (!policy.isObject()) {
            throw invalid("AutoTerminationPolicy must be an object.");
        }
        JsonNode idle = policy.path("IdleTimeout");
        if (!idle.isMissingNode() && (!idle.canConvertToLong()
                || idle.asLong() < MIN_IDLE_TIMEOUT_SECONDS || idle.asLong() > MAX_IDLE_TIMEOUT_SECONDS)) {
            throw invalid("AutoTerminationPolicy.IdleTimeout must be between " + MIN_IDLE_TIMEOUT_SECONDS
                    + " and " + MAX_IDLE_TIMEOUT_SECONDS + " seconds.");
        }
    }

    /**
     * Constraints (MinCapacity not above MaxCapacity) and at least one rule with Name, Action and
     * Trigger are required; only core and task instance groups scale automatically.
     */
    static void validateAutoScalingPolicy(JsonNode policy, String instanceGroupType) {
        if (policy == null || !policy.isObject()) {
            throw invalid("AutoScalingPolicy is required.");
        }
        if (!"CORE".equals(instanceGroupType) && !"TASK".equals(instanceGroupType)) {
            throw invalid("An automatic scaling policy can only be attached to a core or task instance group.");
        }
        JsonNode constraints = policy.path("Constraints");
        if (!constraints.path("MinCapacity").isInt() || !constraints.path("MaxCapacity").isInt()) {
            throw invalid("AutoScalingPolicy.Constraints.MinCapacity and MaxCapacity are required.");
        }
        if (constraints.get("MinCapacity").asInt() < 0
                || constraints.get("MinCapacity").asInt() > constraints.get("MaxCapacity").asInt()) {
            throw invalid("Constraints.MinCapacity must be between 0 and MaxCapacity.");
        }
        JsonNode rules = policy.path("Rules");
        if (!rules.isArray() || rules.isEmpty()) {
            throw invalid("AutoScalingPolicy.Rules must contain at least one rule.");
        }
        for (JsonNode rule : rules) {
            if (rule.path("Name").asText("").isEmpty() || !rule.path("Action").isObject() || !rule.path("Trigger").isObject()) {
                throw invalid("Each scaling rule needs a Name, an Action and a Trigger.");
            }
        }
    }

    /** BlockPublicSecurityGroupRules is required; each port range needs MinRange, within 0 to 65535. */
    static void validateBlockPublicAccess(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()
                || !configuration.path("BlockPublicSecurityGroupRules").isBoolean()) {
            throw invalid("BlockPublicAccessConfiguration.BlockPublicSecurityGroupRules is required.");
        }
        JsonNode ranges = configuration.path("PermittedPublicSecurityGroupRuleRanges");
        if (ranges.isMissingNode()) {
            return;
        }
        if (!ranges.isArray()) {
            throw invalid("PermittedPublicSecurityGroupRuleRanges must be a list.");
        }
        for (JsonNode range : ranges) {
            if (!range.path("MinRange").isInt()) {
                throw invalid("Each port range needs a MinRange.");
            }
            int min = range.get("MinRange").asInt();
            JsonNode maxRange = range.path("MaxRange");
            if (!maxRange.isMissingNode() && !maxRange.isInt()) {
                throw invalid("Port range MaxRange must be an integer.");
            }
            int max = maxRange.isInt() ? maxRange.asInt() : min;
            if (min < 0 || max > MAX_PORT || min > max) {
                throw invalid("Port range " + min + "-" + max + " must lie within 0-" + MAX_PORT + ", MinRange first.");
            }
        }
    }

    /** Each BootstrapActionConfig needs a Name and a ScriptBootstrapAction with a Path. */
    static void validateBootstrapActions(JsonNode actions) {
        if (!actions.isArray()) {
            throw invalid("BootstrapActions must be a list.");
        }
        for (JsonNode action : actions) {
            if (action.path("Name").asText("").isEmpty()
                    || action.path("ScriptBootstrapAction").path("Path").asText("").isEmpty()) {
                throw invalid("Each bootstrap action needs a Name and a ScriptBootstrapAction.Path.");
            }
        }
    }

    private EmrCluster clusterOwning(String region, String instanceGroupId) {
        if (instanceGroupId == null) {
            throw invalid("InstanceGroupId is required.");
        }
        return clusterStore.scan(k -> true).stream()
                .filter(c -> region.equals(c.getRegion()))
                .filter(c -> c.getInstanceGroups().stream().anyMatch(g -> instanceGroupId.equals(g.getId())))
                .findFirst()
                .map(this::settle)
                .orElseThrow(() -> invalid("Instance group " + instanceGroupId + " is not valid."));
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    /** ModifyInstanceFleet's answer for a job flow that is shutting down or ended, ErrorCode member included. */
    private static AwsException jobFlowShutDown() {
        return new AwsException("InvalidRequestException", JOB_FLOW_SHUT_DOWN, 400,
                Map.of("ErrorCode", "INVALID_JOB_SHUT_DOWN"));
    }

    /**
     * AddJobFlowSteps' answer for a job flow that is shutting down or ended. AddInstanceGroups gives it too:
     * like AddJobFlowSteps, its model declares no InvalidRequestException.
     */
    private static AwsException jobFlowShutDownValidation() {
        return new AwsException("ValidationException", JOB_FLOW_SHUT_DOWN, 400);
    }

    /**
     * AddInstanceGroups and AddInstanceFleet on a cluster that is shutting down or ended. A TERMINATING one
     * gets {@code shuttingDown}, the operation's answer with AWS's wording, which covers it; a TERMINATED
     * one keeps Floci's own, since no AWS answer to either operation is recorded.
     */
    private static void rejectShutDownCluster(EmrCluster cluster, Supplier<AwsException> shuttingDown) {
        if (EmrClusterLifecycle.TERMINATING.equals(cluster.getState())) {
            throw shuttingDown.get();
        }
        if (EmrClusterLifecycle.isTerminated(cluster.getState())) {
            throw invalid("Cluster " + cluster.getId() + " is terminated.");
        }
    }

    private static EmrInstanceGroup requireInstanceGroup(EmrCluster cluster, String instanceGroupId) {
        if (instanceGroupId == null) {
            throw invalid("InstanceGroupId is required.");
        }
        return cluster.getInstanceGroups().stream()
                .filter(group -> instanceGroupId.equals(group.getId()))
                .findFirst()
                .orElseThrow(() -> invalid("Instance group " + instanceGroupId + " is not in cluster " + cluster.getId() + "."));
    }

    private EmrCluster requireCluster(String id) {
        if (id == null) {
            throw new AwsException("InvalidRequestException", "ClusterId is required.", 400);
        }
        return findCluster(id).orElseThrow(() -> new AwsException(
                "InvalidRequestException", "Cluster id '" + id + "' is not valid.", 400));
    }

    /**
     * Clusters are stored by id for every region; a request sees only its own region's, and one from
     * another region reads as an unknown id. A cluster recorded without a region stays reachable.
     */
    private Optional<EmrCluster> findCluster(String id) {
        String region = regionResolver.getRegion();
        return clusterStore.get(id).filter(c -> c.getRegion() == null || c.getRegion().equals(region))
                .map(this::settle);
    }

    private void mutateClusters(List<String> ids, Consumer<EmrCluster> mutation) {
        for (String id : ids) {
            findCluster(id).ifPresent(c -> {
                mutation.accept(c);
                clusterStore.put(id, c);
            });
        }
    }

    /** Shutting down or ended: "A job flow that is shutting down, terminated, or finished may not be modified." */
    private static boolean isShutDown(EmrCluster cluster) {
        return EmrClusterLifecycle.TERMINATING.equals(cluster.getState())
                || EmrClusterLifecycle.isTerminated(cluster.getState());
    }

    /** The only states in which a cluster's instance groups and fleets may be modified. */
    private static boolean runsOrWaits(EmrCluster cluster) {
        return EmrClusterLifecycle.RUNNING.equals(cluster.getState())
                || EmrClusterLifecycle.WAITING.equals(cluster.getState());
    }

    /**
     * Runs {@code body} holding the service, then publishes the events it queued once it no longer
     * does, so a rule's target that calls EMR never waits on the call that made the change. Every
     * call that reads a cluster runs this way, since reading one brings it up to date.
     */
    private <T> T call(Supplier<T> body) {
        try {
            synchronized (this) {
                return body.get();
            }
        } finally {
            eventPublisher.publishQueued();
        }
    }

    private void run(Runnable body) {
        call(() -> {
            body.run();
            return null;
        });
    }

    /**
     * A copy of the cluster for a call to answer from: the timer goes on changing the stored one, and an
     * answer built from it after the service is let go could mix two of its states.
     */
    private static EmrCluster snapshotOf(EmrCluster cluster) {
        return SNAPSHOT_MAPPER.convertValue(cluster, EmrCluster.class);
    }

    /**
     * The cluster as it stands now: every change due by now made, stored if there was one. A step a
     * container ran that no thread runs any more, since Floci stopped while it ran, is FAILED.
     */
    private EmrCluster settle(EmrCluster cluster) {
        boolean interrupted = failInterruptedSteps(cluster);
        if (lifecycle.advance(cluster, now(), regionOf(cluster)) || interrupted) {
            clusterStore.put(cluster.getId(), cluster);
        }
        wakeForNextChange(cluster);
        return cluster;
    }

    private boolean failInterruptedSteps(EmrCluster cluster) {
        if (sparkRunner == null) {
            return false;
        }
        boolean failed = false;
        for (EmrStep step : List.copyOf(cluster.getSteps())) {
            if (runsInContainer(step) && !executing.contains(stepKey(regionResolver.getAccountId(), step.getId()))) {
                lifecycle.stepEnded(cluster, step, EmrSparkSteps.INTERRUPTED, now(), regionOf(cluster));
                failed = true;
            }
        }
        return failed;
    }

    private static boolean hasContainerStepRunning(EmrCluster cluster) {
        return cluster.getSteps().stream().anyMatch(EmrService::runsInContainer);
    }

    private static boolean runsInContainer(EmrStep step) {
        return EmrClusterLifecycle.RUNNING.equals(step.getState()) && step.isRunInContainer();
    }

    private static String stepKey(String accountId, String stepId) {
        return accountId + "/" + stepId;
    }

    /**
     * The role a step's driver runs as: the step's runtime role, or else the role of the cluster's EC2
     * instance profile, its {@code JobFlowRole}, given by name or ARN; null when IAM knows neither, and the
     * driver then has the account's placeholder credentials. The guide's "Runtime roles for Amazon EMR
     * steps": without them, clusters "ran Amazon EMR jobs or queries with permissions based on the IAM
     * policy attached to the instance profile that you used to launch the cluster".
     */
    private String executionRole(EmrCluster cluster, EmrStep step, String accountId) {
        if (step.getExecutionRoleArn() != null) {
            return step.getExecutionRoleArn();
        }
        String profile = cluster.getJobFlowRole();
        if (profile == null || iamService == null) {
            return null;
        }
        return iamService.findInstanceProfile(accountId, profile.substring(profile.lastIndexOf('/') + 1))
                .filter(found -> found.getRoleNames() != null && found.getRoleNames().size() == 1)
                .flatMap(found -> iamService.findRole(accountId, found.getRoleNames().getFirst()))
                .map(IamRole::getArn)
                .orElse(null);
    }

    /**
     * Ends a step its thread ran, COMPLETED when {@code failure} is null and FAILED otherwise, unless a
     * reset came since it started or the step is no longer RUNNING: cancelled, or on a cluster that ended.
     */
    private void stepEnded(StepLaunch launch, String failure) {
        if (launch.launchedIn() != generation) {
            return;
        }
        // Read while the step still counts as running, so it is not taken for one a restart left.
        Optional<EmrCluster> found = findCluster(launch.clusterId());
        executing.remove(stepKey(launch.accountId(), launch.stepId()));
        EmrStep step = found.flatMap(cluster -> cluster.getSteps().stream()
                .filter(candidate -> launch.stepId().equals(candidate.getId())).findFirst()).orElse(null);
        if (step == null || !EmrClusterLifecycle.RUNNING.equals(step.getState())) {
            return;
        }
        EmrCluster cluster = found.get();
        lifecycle.stepEnded(cluster, step, failure, now(), regionOf(cluster));
        store(cluster);
        LOG.infov("EMR step {0} of cluster {1} ended {2}", step.getId(), cluster.getId(), step.getState());
    }

    /**
     * A step's driver was stopped. A cancel, or its cluster's end, has ended the step already. One still
     * RUNNING was stopped by Floci itself, by a reset's teardown or its shutdown, and is left as it is, still
     * counted as running: the reset wipes it, a restart fails it, and its ActionOnFailure, which could start
     * the next step after the teardown has passed, is not taken now.
     */
    private void driverStopped(StepLaunch launch) {
        if (!stillRunning(launch)) {
            executing.remove(stepKey(launch.accountId(), launch.stepId()));
        }
    }

    /**
     * Why a step whose driver ended this way, by itself or before it could start, failed, or null when it
     * did not. No source gives AWS's wording for these; they are Floci's.
     */
    private static String failureOf(SparkJobRunner.Job job, SparkJobRunner.Outcome outcome) {
        if (outcome.exitCode() != null) {
            String ended = job.command() != null ? "The step's command" : "The Spark driver";
            return outcome.exitCode() == 0 ? null : ended + " exited with code " + outcome.exitCode() + ".";
        }
        return "The step could not start: " + outcome.failure();
    }

    // Whether a step's driver may still start, or go on: one cancelled, on a cluster that ended, or a reset
    // removed, is past it.
    private synchronized boolean stillRunning(StepLaunch launch) {
        return launch.launchedIn() == generation && clusterStore.get(launch.clusterId()).stream()
                .flatMap(cluster -> cluster.getSteps().stream())
                .anyMatch(step -> launch.stepId().equals(step.getId())
                        && EmrClusterLifecycle.RUNNING.equals(step.getState()));
    }

    /** A step handed to a thread: whose it is, where, and under which reset. */
    private record StepLaunch(String accountId, String region, String clusterId, String stepId, long launchedIn) {}

    /** Starts each step a cluster runs on a thread of its own, which ends the step once it is done. */
    private final class ContainerSteps implements EmrClusterLifecycle.StepExecution {

        @Override
        public void start(EmrCluster cluster, EmrStep step, String region) {
            String accountId = regionResolver.getAccountId();
            List<String> application = EmrSparkSteps.application(step);
            List<String> command = application == null ? EmrSparkSteps.command(step) : null;
            SparkJobRunner.Job job = application == null && command == null ? null
                    : new SparkJobRunner.Job(SERVICE, step.getId(), accountId, region, cluster.getReleaseLabel(),
                    imageTemplate, dockerNetwork, executionRole(cluster, step, accountId),
                    EmrSparkSteps.sparkDefaults(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                    application != null ? application : List.of(), command, Duration.ZERO,
                    EmrSparkSteps.logFolder(cluster, step), null);
            StepLaunch launch = new StepLaunch(accountId, region, cluster.getId(), step.getId(), generation);
            executing.add(stepKey(accountId, step.getId()));
            stepExecutor.execute(() -> runStep(job, launch));
        }

        @Override
        public void stop(EmrStep step) {
            sparkRunner.stop(step.getId());
        }
    }

    // A step's thread: its driver runs to its end, or the step fails at once when Floci does not run its kind.
    private void runStep(SparkJobRunner.Job job, StepLaunch launch) {
        String failure;
        if (job == null) {
            failure = EmrSparkSteps.NOT_RUN;
        } else {
            SparkJobRunner.Outcome outcome;
            try {
                outcome = sparkRunner.run(job, new StepProgress(launch));
            } catch (RuntimeException e) {
                LOG.warnv(e, "EMR step {0} could not run", launch.stepId());
                outcome = SparkJobRunner.Outcome.couldNotStart(e.getMessage());
            }
            if (outcome.stopped()) {
                RequestScopes.runAs(launch.accountId(), launch.region(), () -> run(() -> driverStopped(launch)));
                return;
            }
            failure = failureOf(job, outcome);
        }
        RequestScopes.runAs(launch.accountId(), launch.region(), () -> run(() -> stepEnded(launch, failure)));
    }

    /** What a step's thread asks the service while its driver goes: a step has no timeout to report. */
    private final class StepProgress implements SparkJobRunner.Progress {

        private final StepLaunch launch;

        StepProgress(StepLaunch launch) {
            this.launch = launch;
        }

        @Override
        public void started() {
            // The step has been RUNNING since it started; the driver's start changes nothing a caller sees.
        }

        @Override
        public void timedOut() {
            // A step runs until its driver ends.
        }

        @Override
        public boolean cancelled() {
            return !RequestScopes.callAs(launch.accountId(), launch.region(), () -> stillRunning(launch));
        }
    }

    /** Stores a cluster a call changed, once every change that falls due by now is made. */
    private void store(EmrCluster cluster) {
        lifecycle.advance(cluster, now(), regionOf(cluster));
        clusterStore.put(cluster.getId(), cluster);
        wakeForNextChange(cluster);
    }

    private void wakeForNextChange(EmrCluster cluster) {
        Instant due = lifecycle.nextChange(cluster);
        if (due != null) {
            wakeAt(regionResolver.getAccountId(), cluster, due);
        }
    }

    /**
     * Brings the cluster up to date at {@code due}, as {@code accountId} in its Region, unless an
     * earlier wakeup will: that one sets the next. Its events go out by {@link Wakeups#deliver}.
     */
    private void wakeAt(String accountId, EmrCluster cluster, Instant due) {
        String key = accountId + "/" + cluster.getId();
        Instant set = wakeupsDue.get(key);
        if (set != null && !set.isAfter(due)) {
            return;
        }
        wakeupsDue.put(key, due);
        String id = cluster.getId();
        String region = regionOf(cluster);
        wakeups.at(due, () -> {
            wakeupsDue.remove(key, due);
            try {
                RequestScopes.runAs(accountId, region, () -> {
                    synchronized (this) {
                        findCluster(id);
                    }
                });
            } catch (RuntimeException e) {
                LOG.warnv(e, "EMR could not bring cluster {0} up to date", id);
            }
            wakeups.deliver(eventPublisher::publishQueued);
        });
    }

    private String regionOf(EmrCluster cluster) {
        return cluster.getRegion() != null ? cluster.getRegion() : regionResolver.getRegion();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(UPPER_ALNUM.charAt(RANDOM.nextInt(UPPER_ALNUM.length())));
        }
        return sb.toString();
    }
}
