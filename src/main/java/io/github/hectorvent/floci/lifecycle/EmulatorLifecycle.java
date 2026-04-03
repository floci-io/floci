package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHook;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHooksRunner;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.HttpServerStart;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;

@ApplicationScoped
public class EmulatorLifecycle {

    private static final Logger LOG = Logger.getLogger(EmulatorLifecycle.class);
    private static final int HTTP_PORT = 4566;

    private final StorageFactory storageFactory;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final ElastiCacheProxyManager elastiCacheProxyManager;
    private final RdsProxyManager rdsProxyManager;
    private final InitializationHooksRunner initializationHooksRunner;

    @Inject
    public EmulatorLifecycle(StorageFactory storageFactory, ServiceRegistry serviceRegistry,
                             EmulatorConfig config, ElastiCacheProxyManager elastiCacheProxyManager,
                             RdsProxyManager rdsProxyManager, InitializationHooksRunner initializationHooksRunner) {
        this.storageFactory = storageFactory;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.elastiCacheProxyManager = elastiCacheProxyManager;
        this.rdsProxyManager = rdsProxyManager;
        this.initializationHooksRunner = initializationHooksRunner;
    }

    void onStart(@Observes StartupEvent ignored) {
        LOG.info("=== AWS Local Emulator Starting ===");
        LOG.infov("Storage mode: {0}", config.storage().mode());
        LOG.infov("Persistent path: {0}", config.storage().persistentPath());

        serviceRegistry.logEnabledServices();
        storageFactory.loadAll();

        if (!initializationHooksRunner.hasHooks(InitializationHook.START)) {
            LOG.info("=== AWS Local Emulator Ready ===");
        }
    }

    void onHttpStart(@ObservesAsync HttpServerStart event) {
        if ((event.options().getPort() == HTTP_PORT) &&
            initializationHooksRunner.hasHooks(InitializationHook.START)){
            try {
                initializationHooksRunner.run(InitializationHook.START);
                LOG.info("=== AWS Local Emulator Ready ===");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.error("Startup hook execution interrupted — shutting down", e);
            } catch (Exception e) {
                LOG.error("Startup hook execution failed — shutting down", e);
                Quarkus.asyncExit();
            }
        }
    }

    void onStop(@Observes ShutdownEvent ignored) throws IOException, InterruptedException {
        LOG.info("=== AWS Local Emulator Shutting Down ===");

        try {
            initializationHooksRunner.run(InitializationHook.STOP);
        } catch (IOException | InterruptedException e) {
            LOG.error("Shutdown hook execution failed", e);
            throw e;
        } catch (RuntimeException e) {
            LOG.error("Shutdown hook script failed", e);
        } finally {
            elastiCacheProxyManager.stopAll();
            rdsProxyManager.stopAll();
            storageFactory.shutdownAll();
        }

        LOG.info("=== AWS Local Emulator Stopped ===");
    }
}
