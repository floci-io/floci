package io.floci.aws.services.amazonmq;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StripedLocks;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.resource.ExplorerResource;
import io.floci.aws.core.resource.ResourceProvider;
import io.floci.aws.core.resource.SupportedResourceType;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.amazonmq.container.RabbitMqManager;
import io.floci.aws.services.amazonmq.model.Broker;
import io.floci.aws.services.amazonmq.model.BrokerInstance;
import io.floci.aws.services.amazonmq.model.BrokerState;
import io.floci.aws.services.amazonmq.model.MqUser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class AmazonMqService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(AmazonMqService.class);
    private static final String ENGINE_RABBITMQ = "RABBITMQ";
    private static final String DEFAULT_ENGINE_VERSION = "3.13";
    private static final String DEPLOYMENT_SINGLE_INSTANCE = "SINGLE_INSTANCE";
    private static final String KEY_SEPARATOR = "::";

    /** Brokers by {@code region::brokerId}, in their owner's account partition: brokers are Regional. */
    private final AccountAwareStorageBackend<Broker> storage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final RabbitMqManager rabbitMqManager;
    private final StripedLocks brokerLocks = new StripedLocks(256);
    /**
     * The stored brokers' locks, apart from the name locks a create holds while its container starts, so the
     * readiness poller and a DeleteBroker never wait on a create of another broker.
     */
    private final StripedLocks storedBrokerLocks = new StripedLocks(256);
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public AmazonMqService(StorageFactory storageFactory, EmulatorConfig config,
                           RegionResolver regionResolver, RabbitMqManager rabbitMqManager) {
        this.storage = storageFactory.create("amazonmq", "amazonmq-brokers.json",
                new TypeReference<Map<String, Broker>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.rabbitMqManager = rabbitMqManager;
    }

    @PostConstruct
    public void init() {
        migrateLegacyKeys();
        startReadinessPoller();
    }

    @PreDestroy
    public void shutdown() {
        // Container teardown is wired into EmulatorLifecycle.onStop() via
        // RabbitMqManager.stopAll() (ordered with the other container managers);
        // here we only stop the readiness poller.
        poller.shutdown();
    }

    public Broker createBroker(CreateBrokerParams params) {
        String name = params.brokerName();
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "BrokerName is required", 400);
        }
        // EngineType is case-insensitive on real AWS, and the mixed-case spelling is the
        // one the AWS docs, the console and the aws_mq_broker registry examples all use --
        // so an exact match rejects the form virtually every Terraform module is written
        // with. Distinguish the unsupported engine from an unrecognised one so the two
        // failures are not reported identically.
        if (!ENGINE_RABBITMQ.equalsIgnoreCase(params.engineType())) {
            throw new AwsException("BadRequestException",
                    "EngineType " + params.engineType() + " is not supported; only RabbitMQ is emulated",
                    400);
        }
        String deploymentMode = params.deploymentMode() == null
                ? DEPLOYMENT_SINGLE_INSTANCE : params.deploymentMode();
        // DeploymentMode has the same problem as EngineType above: the wire enum is upper case,
        // but callers spell it however their tooling does, and the real API matches without
        // regard to case. An exact compare rejected "single_instance" -- the one mode this
        // emulator does support -- as unsupported.
        if (!DEPLOYMENT_SINGLE_INSTANCE.equalsIgnoreCase(deploymentMode)) {
            throw new AwsException("BadRequestException",
                    "Only SINGLE_INSTANCE DeploymentMode is supported", 400);
        }
        // Store the canonical wire casing, not the caller's, so DescribeBroker reads back the
        // enum value the SDKs expect however the request happened to be spelled.
        deploymentMode = DEPLOYMENT_SINGLE_INSTANCE;
        // RabbitMQ brokers require exactly one user at creation; that user becomes the
        // broker's RabbitMQ administrator (seeded into the container). This mirrors AWS,
        // which rejects CreateBroker for RabbitMQ unless exactly one user is supplied.
        List<MqUser> requestedUsers = params.users() == null ? List.of() : params.users();
        if (requestedUsers.size() != 1) {
            throw new AwsException("BadRequestException",
                    "Exactly one broker user is required for a RabbitMQ broker", 400);
        }
        MqUser admin = requestedUsers.get(0);
        if (admin.getUsername() == null || admin.getUsername().isBlank()) {
            throw new AwsException("BadRequestException", "Broker user username is required", 400);
        }
        validateUserPassword(admin.getPassword());

        String region = regionResolver.getRegion();
        String accountId = regionResolver.getAccountId();
        // A broker name is unique in the account, across its Regions, as the CreateBroker reference documents.
        // The name check and the store hold one lock for the name in its account, so two creates of one name
        // can't both find it free.
        synchronized (brokerLocks.lockFor(accountId + ":" + name)) {
            if (storage.scan(key -> true).stream().anyMatch(b -> name.equals(b.getBrokerName()))) {
                throw new AwsException("ConflictException", "Broker already exists: " + name, 409);
            }

            String brokerId = "b-" + UUID.randomUUID();
            String brokerArn = AwsArnUtils.Arn.of("mq", region, accountId,
                    "broker:" + name + ":" + brokerId).toString();
            String engineVersion = (params.engineVersion() == null || params.engineVersion().isBlank())
                    ? DEFAULT_ENGINE_VERSION : params.engineVersion();

            Broker broker = new Broker(brokerId, brokerArn, name, ENGINE_RABBITMQ,
                    engineVersion, deploymentMode, params.hostInstanceType());
            broker.setAccountId(accountId);
            broker.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));
            // Stamp the volume name now, with the current prefix, so it is persisted rather than
            // recomputed later. Only records predating this field fall back to the legacy name.
            broker.setDockerVolumeName(ContainerStorageHelper.resourceName(
                    config, "amazonmq", broker.getVolumeId(), broker.getBrokerId()));
            broker.setPubliclyAccessible(params.publiclyAccessible());
            broker.setAutoMinorVersionUpgrade(params.autoMinorVersionUpgrade());
            if (params.users() != null) {
                broker.setUsers(new ArrayList<>(params.users()));
            }
            if (params.tags() != null) {
                broker.setTags(new HashMap<>(params.tags()));
            }
            broker.setSecurityGroups(params.securityGroups());
            broker.setSubnetIds(params.subnetIds());
            broker.setLogs(params.logs());
            broker.setMaintenanceWindowStartTime(params.maintenanceWindowStartTime());
            broker.setStorageType(params.storageType());
            broker.setAuthenticationStrategy(params.authenticationStrategy());
            broker.setEncryptionOptions(params.encryptionOptions());
            broker.setConfiguration(params.configuration());

            if (config.services().amazonmq().mock()) {
                // No backing container: come up immediately with synthetic endpoints.
                applyLocalEndpoints(broker);
                broker.setBrokerState(BrokerState.RUNNING);
            } else {
                try {
                    // Start the container; the broker stays CREATION_IN_PROGRESS until
                    // the readiness poller observes the management API answering.
                    rabbitMqManager.startContainer(broker);
                } catch (RuntimeException e) {
                    broker.setBrokerState(BrokerState.CREATION_FAILED);
                    storage.put(brokerKey(region, brokerId), broker);
                    // Keep the cause in the logs; don't leak internal details (or a null
                    // message) into the AWS error envelope returned to the client.
                    LOG.errorv(e, "Failed to provision broker {0} ({1})", name, brokerId);
                    throw new AwsException("InternalServerErrorException",
                            "Failed to provision broker " + name, 500);
                }
            }

            storage.put(brokerKey(region, brokerId), broker);
            LOG.infov("Created Amazon MQ broker {0} ({1})", name, brokerId);
            return broker;
        }
    }

    public Broker describeBroker(String brokerId) {
        return storage.get(brokerKey(regionResolver.getRegion(), brokerId))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Broker not found: " + brokerId, 404));
    }

    /** The calling account's brokers in the request's Region. */
    public List<Broker> listBrokers() {
        String prefix = regionResolver.getRegion() + KEY_SEPARATOR;
        return storage.scan(key -> key.startsWith(prefix));
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Broker broker : storage.scan(k -> true)) {
            String arn = broker.getBrokerArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "mq:broker", "mq",
                    parsed.region(), parsed.accountId(),
                    broker.getCreated() != null ? broker.getCreated() : Instant.now(),
                    broker.getTags() != null ? broker.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("mq:broker", "mq", true));
    }

    public void deleteBroker(String brokerId) {
        String key = brokerKey(regionResolver.getRegion(), brokerId);
        Object lock = storedBrokerLock(regionResolver.getAccountId(), key);
        Broker broker;
        // The broker is read and marked, and later removed, under its lock, which the readiness poller holds while
        // it reads the broker again and writes it back: a broker turning ready while it is deleted is not stored
        // again as RUNNING, and a second delete either marks the broker before the removal or finds it gone, so it
        // never stores it again after the removal. The container stops outside the lock.
        synchronized (lock) {
            broker = describeBroker(brokerId);
            broker.setBrokerState(BrokerState.DELETION_IN_PROGRESS);
            storage.put(key, broker);
        }
        if (!config.services().amazonmq().mock()) {
            rabbitMqManager.stopContainer(broker);
            rabbitMqManager.removeBrokerStorage(broker);
        }
        synchronized (lock) {
            storage.delete(key);
        }
        LOG.infov("Deleted Amazon MQ broker {0}", brokerId);
    }

    public Broker rebootBroker(String brokerId) {
        Broker broker = describeBroker(brokerId);
        // AWS allows RebootBroker only on a broker in the RUNNING state. Without
        // this guard a non-RUNNING broker (e.g. CREATION_FAILED, which has no
        // backing container) would be silently promoted to RUNNING and never
        // reconciled by the readiness poller.
        if (broker.getBrokerState() != BrokerState.RUNNING) {
            throw new AwsException("BadRequestException",
                    "Broker " + brokerId + " cannot be rebooted while in state "
                            + broker.getBrokerState() + "; it must be RUNNING", 400);
        }
        // RebootBroker is asynchronous and returns the broker to RUNNING. This tier
        // does not cycle the container, so the broker simply stays RUNNING.
        return broker;
    }

    private void applyLocalEndpoints(Broker broker) {
        BrokerInstance instance = new BrokerInstance(
                "http://localhost:15672",
                List.of("amqp://localhost:5672"),
                "localhost");
        broker.setBrokerInstances(new ArrayList<>(List.of(instance)));
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(this::pollReadiness, 1, 2, TimeUnit.SECONDS);
    }

    /** One pass of the readiness poller: marks each starting broker whose container answers {@code RUNNING}. */
    void pollReadiness() {
        try {
            if (config.services().amazonmq().mock()) {
                return;
            }
            // Every account's brokers, each written back under the account and key it is stored
            // under: the poller has no request context, so a plain put would land in the default
            // account. Brokers stored under a bare id have moved to their Region's key at startup.
            for (AccountEntry<Broker> entry : storage.scanAllAccountEntries(key -> true)) {
                Broker broker = entry.value();
                if (broker.getBrokerState() == BrokerState.CREATION_IN_PROGRESS
                        && rabbitMqManager.isReady(broker)) {
                    markRunning(entry.accountId(), entry.key());
                }
            }
        } catch (Exception e) {
            LOG.error("Error in Amazon MQ readiness poller", e);
        }
    }

    /**
     * Stores a broker that turned ready as RUNNING, read again under its lock first. The readiness check is a
     * blocking call to the broker's container, long enough for a DeleteBroker, which marks the broker under the same
     * lock, to land in the meantime, and a broker deleted or being deleted is not written back.
     */
    private void markRunning(String accountId, String key) {
        synchronized (storedBrokerLock(accountId, key)) {
            Broker broker = storage.getForAccount(accountId, key).orElse(null);
            if (broker == null || broker.getBrokerState() != BrokerState.CREATION_IN_PROGRESS) {
                return;
            }
            LOG.infov("Amazon MQ broker {0} is now RUNNING", broker.getBrokerName());
            broker.setBrokerState(BrokerState.RUNNING);
            storage.putForAccount(accountId, key, broker);
        }
    }

    /** The lock of the broker stored under {@code key} in {@code accountId}. */
    private Object storedBrokerLock(String accountId, String key) {
        return storedBrokerLocks.lockFor(accountId + ":" + key);
    }

    static String brokerKey(String region, String brokerId) {
        return region + KEY_SEPARATOR + brokerId;
    }

    /** A broker's key in its own Region, which its ARN has always named. */
    private String brokerKey(Broker broker) {
        return brokerKey(broker.region(regionResolver.getDefaultRegion()), broker.getBrokerId());
    }

    /**
     * Moves brokers persisted before they were keyed by Region, in every account, from the bare
     * broker id onto the Region-scoped key, in the Region their ARN names. Each stays in the
     * account partition that stored it.
     */
    private void migrateLegacyKeys() {
        storage.migrateLegacyEntriesInAllAccounts(key -> !key.contains(KEY_SEPARATOR), this::brokerKey);
    }

    // --- Users ---
    // Amazon MQ's standalone User API (CreateUser/DescribeUser/ListUsers/UpdateUser/
    // DeleteUser) applies only to ActiveMQ brokers. For RabbitMQ, AWS rejects these
    // operations and directs callers to the RabbitMQ web console. Every broker we host
    // is RabbitMQ, so they always reject. The broker's admin user is seeded once at
    // CreateBroker time; additional users are managed through the RabbitMQ console.

    public MqUser createUser(String brokerId, MqUser user) {
        throw userApiNotSupported();
    }

    public MqUser describeUser(String brokerId, String username) {
        throw userApiNotSupported();
    }

    public List<MqUser> listUsers(String brokerId) {
        throw userApiNotSupported();
    }

    public void deleteUser(String brokerId, String username) {
        throw userApiNotSupported();
    }

    private static AwsException userApiNotSupported() {
        return new AwsException("BadRequestException",
                "User management API operations do not apply to RabbitMQ brokers. "
                        + "Manage users through the RabbitMQ web console.", 400);
    }

    /**
     * Enforces Amazon MQ's broker-user password rule: at least 12 characters, at
     * least 4 unique characters, and no commas, colons, or equal signs.
     */
    private static void validateUserPassword(String password) {
        if (password == null || password.length() < 12) {
            throw new AwsException("BadRequestException",
                    "Broker user password must be at least 12 characters long", 400);
        }
        if (password.chars().distinct().count() < 4) {
            throw new AwsException("BadRequestException",
                    "Broker user password must contain at least 4 unique characters", 400);
        }
        if (password.contains(",") || password.contains(":") || password.contains("=")) {
            throw new AwsException("BadRequestException",
                    "Broker user password must not contain commas, colons, or equal signs", 400);
        }
    }
}
