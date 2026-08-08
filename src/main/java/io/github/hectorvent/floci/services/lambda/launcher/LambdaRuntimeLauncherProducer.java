package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.kubernetes.KubernetesPodLauncher;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.jboss.logging.Logger;

import java.util.Locale;

/**
 * Selects the Lambda execution backend at startup. {@code Instance<>} lookups keep
 * the unselected launcher (and its Docker/Kubernetes client) uninstantiated, so a
 * Docker-less cluster deployment never touches docker.sock and vice versa.
 */
@ApplicationScoped
public class LambdaRuntimeLauncherProducer {

    private static final Logger LOG = Logger.getLogger(LambdaRuntimeLauncherProducer.class);

    /**
     * The producer itself is lazy, so a bad value would otherwise surface only on the
     * first invocation. This makes a misconfigured executor fail startup instead.
     *
     * <p>For the kubernetes executor the client is also initialized here, on the
     * startup thread. Left to the first cold start, the Fabric8 client's Vert.x
     * HTTP client would be created on a request's Vert.x context and closed with
     * the HTTP server verticles at shutdown, so the WarmPool drain could no longer
     * delete pods ("Client is closed").
     */
    void validateExecutor(@Observes StartupEvent event, EmulatorConfig config,
                          Instance<KubernetesPodLauncher> kubernetes) {
        if (requireValidExecutor(config).equals("kubernetes")) {
            kubernetes.get().initializeClient();
        }
    }

    @Produces
    @ApplicationScoped
    LambdaRuntimeLauncher launcher(EmulatorConfig config,
                                   Instance<ContainerLauncher> docker,
                                   Instance<KubernetesPodLauncher> kubernetes) {
        var executor = requireValidExecutor(config);
        if (executor.equals("kubernetes")) {
            LOG.info("Lambda executor: kubernetes (environments run as pods)");
            return kubernetes.get();
        }
        return docker.get();
    }

    private static String requireValidExecutor(EmulatorConfig config) {
        var executor = config.services().lambda().executor().trim().toLowerCase(Locale.ROOT);
        if (!executor.equals("docker") && !executor.equals("kubernetes")) {
            throw new IllegalArgumentException(
                    "Unknown floci.services.lambda.executor '" + executor
                            + "'. Valid values: docker, kubernetes");
        }
        return executor;
    }
}
