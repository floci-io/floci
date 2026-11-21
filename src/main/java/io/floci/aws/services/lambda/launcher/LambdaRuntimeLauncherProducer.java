package io.floci.aws.services.lambda.launcher;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.services.lambda.launcher.kubernetes.KubernetesPodLauncher;
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
     *
     * <p>The process executor is refused inside a container, where the host it would run
     * functions on is that container rather than the machine the user develops on.
     */
    void validateExecutor(@Observes StartupEvent event, EmulatorConfig config,
                          Instance<KubernetesPodLauncher> kubernetes, ContainerDetector containerDetector) {
        String executor = requireValidExecutor(config);
        if (executor.equals("kubernetes")) {
            kubernetes.get().initializeClient();
        }
        if (executor.equals("process") && containerDetector.isRunningInContainer()) {
            throw new IllegalStateException("floci.services.lambda.executor=process runs functions as processes "
                    + "of the machine Floci runs on, but Floci is running inside a container. "
                    + "Use the docker or kubernetes executor there.");
        }
    }

    void reconcileDockerContainers(@Observes StartupEvent event, EmulatorConfig config,
                                   Instance<ContainerLauncher> docker) {
        if (config.services().lambda().enabled() && config.services().lambda().reconcileContainersOnStartup()
                && requireValidExecutor(config).equals("docker")) {
            docker.get().removeLeftoverContainers();
        }
    }

    @Produces
    @ApplicationScoped
    LambdaRuntimeLauncher launcher(EmulatorConfig config,
                                   Instance<ContainerLauncher> docker,
                                   Instance<KubernetesPodLauncher> kubernetes,
                                   Instance<ProcessLauncher> process) {
        String executor = requireValidExecutor(config);
        if (executor.equals("kubernetes")) {
            LOG.info("Lambda executor: kubernetes (environments run as pods)");
            return kubernetes.get();
        }
        if (executor.equals("process")) {
            LOG.info("Lambda executor: process (environments run as local processes)");
            return process.get();
        }
        return docker.get();
    }

    private static String requireValidExecutor(EmulatorConfig config) {
        String executor = config.services().lambda().executor().trim().toLowerCase(Locale.ROOT);
        if (!executor.equals("docker") && !executor.equals("kubernetes") && !executor.equals("process")) {
            throw new IllegalArgumentException(
                    "Unknown floci.services.lambda.executor '" + executor
                            + "'. Valid values: docker, kubernetes, process");
        }
        return executor;
    }
}
