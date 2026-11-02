package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.services.rds.container.RdsBackendGate;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
                binding, username -> binding != null, backendGate, endpointKind);
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
