package io.floci.aws.services.rds.proxy;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.services.rds.container.RdsBackendGate;
import io.floci.aws.services.rds.container.RdsContainerHandle;
import io.floci.aws.services.rds.container.RdsContainerManager;
import io.floci.aws.services.rds.model.DatabaseEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Registry of all active RDS auth proxies. One proxy per DB instance or cluster.
 */
@ApplicationScoped
public class RdsProxyManager {

    private static final Logger LOG = Logger.getLogger(RdsProxyManager.class);

    private final RdsSigV4Validator sigV4Validator;
    private final RdsProxyTlsCertificates tlsCertificates;
    private final EmulatorConfig config;
    private final RdsBackendGate backendGate;
    private final RdsContainerManager containerManager;
    private final ConcurrentHashMap<String, RdsAuthProxy> proxies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RdsAuthProxy> pendingFailedStarts = new ConcurrentHashMap<>();
    private volatile Predicate<String> forceSsl = instanceId -> false;

    @Inject
    public RdsProxyManager(RdsSigV4Validator sigV4Validator, RdsProxyTlsCertificates tlsCertificates,
                           EmulatorConfig config, RdsBackendGate backendGate,
                           RdsContainerManager containerManager) {
        this.sigV4Validator = sigV4Validator;
        this.tlsCertificates = tlsCertificates;
        this.config = config;
        this.backendGate = backendGate;
        this.containerManager = containerManager;
    }

    public synchronized void startProxy(String instanceId, String backendRuntimeId,
                                        DatabaseEngine engine, boolean iamEnabled,
                                        int proxyPort, String backendHost, int backendPort,
                                        String advertisedHost,
                                        String masterUsername, String masterPassword, String dbName,
                                        RdsAuthProxy.MasterPasswordCheck passwordValidator,
                                        RdsProxyBinding binding) {
        startProxy(instanceId, backendRuntimeId, engine, iamEnabled, proxyPort, backendHost,
                backendPort, advertisedHost, masterUsername, masterPassword, dbName,
                passwordValidator, binding, RdsAuthProxy.EndpointKind.DATABASE);
    }

    public synchronized void startProxy(String instanceId, String backendRuntimeId,
                                        DatabaseEngine engine, boolean iamEnabled,
                                        int proxyPort, String backendHost, int backendPort,
                                        String advertisedHost,
                                        String masterUsername, String masterPassword, String dbName,
                                        RdsAuthProxy.MasterPasswordCheck passwordValidator,
                                        RdsProxyBinding binding, RdsAuthProxy.EndpointKind endpointKind) {
        if (containerManager == null) {
            throw new IllegalStateException("RDS container manager is required for dynamic backend resolution");
        }
        AtomicBoolean endpointFailureLogged = new AtomicBoolean();
        RdsAuthProxy.BackendEndpointResolver endpointResolver = () -> {
            try {
                EndpointInfo endpoint = containerManager.resolveBackendEndpoint(backendRuntimeId, engine);
                endpointFailureLogged.set(false);
                return new RdsAuthProxy.BackendEndpoint(endpoint.host(), endpoint.port());
            } catch (RuntimeException e) {
                if (endpointFailureLogged.compareAndSet(false, true)) {
                    LOG.warnv(e, "Could not resolve backend endpoint for RDS runtime {0}",
                            backendRuntimeId);
                }
                throw new IOException(
                        "Could not resolve backend endpoint for RDS runtime " + backendRuntimeId, e);
            }
        };
        startProxy(instanceId, backendRuntimeId, engine, iamEnabled, proxyPort,
                backendHost, backendPort, endpointResolver, advertisedHost, masterUsername,
                masterPassword, dbName, passwordValidator, binding, endpointKind);
    }

    /**
     * The rdsadmin login of the runtime's current PostgreSQL container, which an IAM session is handed over from,
     * or null where the master user is the container's superuser.
     */
    private RdsAuthProxy.HandoverLoginResolver handoverLogin(String backendRuntimeId) {
        return () -> {
            RdsContainerHandle.PostgresSuperuser superuser = containerManager.postgresHandoverLogin(backendRuntimeId);
            return superuser == null ? null
                    : new PostgresProtocolHandler.BackendLogin(superuser.user(), superuser.password());
        };
    }

    private void startProxy(String instanceId, String backendRuntimeId,
                            DatabaseEngine engine, boolean iamEnabled,
                            int proxyPort, String backendHost, int backendPort,
                            RdsAuthProxy.BackendEndpointResolver endpointResolver,
                            String advertisedHost,
                            String masterUsername, String masterPassword, String dbName,
                            RdsAuthProxy.MasterPasswordCheck passwordValidator,
                            RdsProxyBinding binding, RdsAuthProxy.EndpointKind endpointKind) {
        if (instanceId == null) {
            throw new IllegalArgumentException("RDS proxy instance ID is required");
        }
        RdsAuthProxy pending = pendingFailedStarts.get(instanceId);
        if (pending != null) {
            pending.stop();
            pendingFailedStarts.remove(instanceId, pending);
        }
        tlsCertificates.ensureHost(advertisedHost);
        EmulatorConfig.RdsServiceConfig rdsConfig = config.services().rds();
        RdsAuthProxy proxy = new RdsAuthProxy(
                instanceId, backendHost, backendPort, endpointResolver, backendRuntimeId,
                engine, iamEnabled, masterUsername, masterPassword, dbName, sigV4Validator,
                tlsCertificates, passwordValidator, rdsConfig.proxyHandshakeTimeoutMillis(),
                rdsConfig.proxyBackendConnectTimeoutMillis(), rdsConfig.proxyMaxConnections(),
                binding, username -> binding != null, backendGate, endpointKind,
                () -> forceSsl.test(instanceId), handoverLogin(backendRuntimeId));
        try {
            proxy.start(proxyPort);
        } catch (IOException e) {
            RuntimeException failure = new RuntimeException(
                    "Failed to start RDS proxy for instance " + instanceId
                            + " on port " + proxyPort, e);
            cleanupFailedStart(instanceId, proxy, failure);
            throw failure;
        } catch (RuntimeException e) {
            RuntimeException failure = new RuntimeException(
                    "Failed to register RDS proxy for instance " + instanceId
                            + " on port " + proxyPort, e);
            cleanupFailedStart(instanceId, proxy, failure);
            throw failure;
        } catch (Error e) {
            cleanupFailedStart(instanceId, proxy, e);
            throw e;
        }
        RdsAuthProxy previous;
        try {
            previous = proxies.put(instanceId, proxy);
        } catch (RuntimeException e) {
            RuntimeException failure = new RuntimeException(
                    "Failed to register RDS proxy for instance " + instanceId
                            + " on port " + proxyPort, e);
            cleanupFailedStart(instanceId, proxy, failure);
            throw failure;
        } catch (Error e) {
            cleanupFailedStart(instanceId, proxy, e);
            throw e;
        }
        if (previous != null) {
            try {
                previous.stop();
            } catch (RuntimeException | Error e) {
                proxies.put(instanceId, previous);
                RuntimeException failure = new RuntimeException(
                        "Failed to replace RDS proxy for instance " + instanceId, e);
                cleanupFailedStart(instanceId, proxy, failure);
                throw failure;
            }
        }
    }

    public void forceSslWhen(Predicate<String> forceSsl) {
        this.forceSsl = forceSsl;
    }

    /**
     * Puts {@code host} on the proxies' TLS certificate, for a name clients reach a proxy by besides the one it
     * advertises, such as a DB cluster's endpoint names, which resolve to its instances.
     */
    public void coverTlsHost(String host) {
        tlsCertificates.ensureHost(host);
    }

    public synchronized void updateMasterPassword(String instanceId, String newPassword) {
        RdsAuthProxy proxy = proxies.get(instanceId);
        if (proxy != null) {
            proxy.updateMasterPassword(newPassword);
            LOG.infov("Updated RDS proxy master password for instance {0}", instanceId);
        }
    }

    public synchronized void updateIamEnabled(String instanceId, boolean enabled) {
        RdsAuthProxy proxy = proxies.get(instanceId);
        if (proxy != null) {
            proxy.updateIamEnabled(enabled);
            LOG.infov("Updated RDS proxy IAM authentication for instance {0}", instanceId);
        }
    }

    public synchronized void stopProxy(String instanceId) {
        Throwable failure = null;
        RdsAuthProxy pending = pendingFailedStarts.get(instanceId);
        if (pending != null) {
            try {
                pending.stop();
                pendingFailedStarts.remove(instanceId, pending);
            } catch (RuntimeException | Error e) {
                failure = e;
            }
        }
        RdsAuthProxy proxy = proxies.get(instanceId);
        if (proxy != null) {
            try {
                proxy.stop();
                proxies.remove(instanceId, proxy);
                LOG.infov("Stopped RDS proxy for instance {0}", instanceId);
            } catch (RuntimeException | Error e) {
                if (failure == null) {
                    failure = e;
                } else if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    public synchronized void stopAll() {
        Set<String> instanceIds = new HashSet<>(proxies.keySet());
        instanceIds.addAll(pendingFailedStarts.keySet());
        instanceIds.forEach(instanceId -> {
            try {
                stopProxy(instanceId);
            } catch (RuntimeException | Error e) {
                LOG.warnv(e, "Failed to stop RDS proxy for instance {0} during shutdown",
                        instanceId);
            }
        });
        LOG.info("Stopped all RDS proxies");
    }

    private void cleanupFailedStart(String instanceId, RdsAuthProxy proxy, Throwable failure) {
        try {
            proxy.stop();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
            pendingFailedStarts.put(instanceId, proxy);
        }
    }
}
