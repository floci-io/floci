package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.DockerClientProducer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Says once, at startup, what an unreachable Docker daemon costs.
 *
 * <p>Floci starts without Docker, and most services never need it. The container-backed ones
 * either keep serving their control plane with no container behind it (metadata-only) or fail
 * when asked to run something. Each of them otherwise reports the missing daemon on its own and
 * only on first use, so one line up front tells a user of the native binary which services are
 * affected before anything fails. Nothing is logged when Docker answers, or when no affected
 * service is enabled.
 */
@ApplicationScoped
public class DockerAvailabilityReport {

    private static final Logger LOG = Logger.getLogger(DockerAvailabilityReport.class);

    /** Ping Docker, report and carry on: their control plane works without a container. */
    static final List<String> METADATA_ONLY = List.of(
            "rds", "elasticache", "memorydb", "opensearch", "neptune", "docdb", "msk", "eks",
            "timestreaminfluxdb", "ec2");

    /** Run a container per request and fail that request (or its build, task, job) without Docker. */
    static final List<String> FAIL_ON_USE = List.of(
            "lambda", "ecs", "codebuild", "batch", "sagemaker", "appsync", "pipes", "mwaa",
            "amazonmq", "kinesisanalytics", "redshift", "redshift-serverless", "rds-data", "athena", "ecr",
            "codeartifact", "verifiedpermissions");

    private final ContainerLifecycleManager lifecycleManager;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;

    @Inject
    public DockerAvailabilityReport(ContainerLifecycleManager lifecycleManager, ServiceRegistry serviceRegistry,
                                    EmulatorConfig config) {
        this.lifecycleManager = lifecycleManager;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
    }

    void onStart(@Observes StartupEvent ignored) {
        // A missing socket fails fast, but a daemon that accepts and never answers waits for the
        // API timeout, so the probe stays off the startup path.
        Thread.ofVirtual().name("floci-docker-availability").start(this::report);
    }

    void report() {
        Set<String> enabled = Set.copyOf(serviceRegistry.getEnabledServices());
        List<String> metadataOnly = metadataOnly(enabled);
        List<String> failOnUse = failOnUse(enabled);
        if (metadataOnly.isEmpty() && failOnUse.isEmpty()) {
            return;
        }
        String reason = unreachableReason();
        if (reason == null) {
            return;
        }
        LOG.warnv("Docker is not reachable at {0} ({1}). Without it these services keep their control plane but"
                        + " run no containers: {2}; and these fail when asked to run one: {3}."
                        + " Everything else works without Docker.",
                DockerClientProducer.resolveDockerConnection(config.docker(), System::getenv).host(), reason,
                metadataOnly, failOnUse);
    }

    List<String> metadataOnly(Set<String> enabled) {
        return METADATA_ONLY.stream()
                .filter(enabled::contains)
                .filter(service -> !needsDockerWhenConfigured(service))
                .toList();
    }

    List<String> failOnUse(Set<String> enabled) {
        return Stream.concat(METADATA_ONLY.stream().filter(this::needsDockerWhenConfigured), FAIL_ON_USE.stream())
                .filter(enabled::contains)
                .filter(service -> !runsWithoutLocalDocker(service))
                .map(service -> "pipes".equals(service) ? "pipes (Kafka sources)" : service)
                .toList();
    }

    /**
     * A metadata-only service that a setting makes fail without Docker: with security-group
     * enforcement on, an EC2 launch with no daemon fails instead of running as metadata only.
     */
    boolean needsDockerWhenConfigured(String service) {
        return "ec2".equals(service) && config.network().securityGroupEnforcement().enabled();
    }

    /** A service whose container work goes somewhere other than the local daemon. */
    boolean runsWithoutLocalDocker(String service) {
        return switch (service) {
            case "lambda" -> "kubernetes".equalsIgnoreCase(config.services().lambda().executor().trim());
            case "athena" -> isSet(config.services().duck().url());
            case "verifiedpermissions" -> isSet(config.services().verifiedpermissions().cedarUrl());
            default -> false;
        };
    }

    private static boolean isSet(Optional<String> url) {
        return url.isPresent() && !url.get().isBlank();
    }

    /** @return why Docker is unreachable, or {@code null} when it answers */
    String unreachableReason() {
        try {
            lifecycleManager.getDockerClient().pingCmd().exec();
            return null;
        } catch (RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }
}
