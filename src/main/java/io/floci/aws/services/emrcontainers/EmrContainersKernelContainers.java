package io.floci.aws.services.emrcontainers;

import io.floci.aws.services.emrserverless.SparkJobRunner;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The containers of the kernels an endpoint's {@code jupyter-kernel-overrides} gives an image of their own ("Custom
 * kernel image with interactive endpoint"), each kernel in a container of its own, as on EMR on EKS each is a pod of
 * its image. The endpoint's gateway asks for a kernel's container as it starts the kernel, and for its end as it stops
 * it, with a secret Floci gives that gateway alone; an endpoint's containers end with its gateway.
 */
final class EmrContainersKernelContainers {

    private static final Logger LOG = Logger.getLogger(EmrContainersKernelContainers.class);

    /** A kernel's id, as jupyter_client makes one. */
    static final Pattern KERNEL_ID = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    /** Where a kernel's launcher sends where it listens: a host on the gateway's network, and a port. */
    static final Pattern RESPONSE_ADDRESS = Pattern.compile("[0-9A-Za-z.-]{1,253}:[0-9]{1,5}");
    /** The public key the launcher encrypts that with, in base64. */
    static final Pattern PUBLIC_KEY = Pattern.compile("[A-Za-z0-9+/]{1,4096}={0,2}");

    private final SparkJobRunner runner;
    private final Executor executor;
    // Each gateway's secret, account and Region, by its endpoint.
    private final Map<String, Gateway> gateways = new ConcurrentHashMap<>();
    // The run of each kernel's container, by endpoint, then kernel.
    private final Map<String, Map<String, Kernel>> kernels = new HashMap<>();
    private final AtomicLong launches = new AtomicLong();

    EmrContainersKernelContainers(SparkJobRunner runner, Executor executor) {
        this.runner = runner;
        this.executor = executor;
    }

    /** What a gateway was given: its secret, and its endpoint's account and Region. */
    record Gateway(String secret, String accountId, String region) {
    }

    // A kernel's container's run, and whether it was asked to stop.
    private static final class Kernel {
        final String runId;
        volatile boolean stopped;

        Kernel(String runId) {
            this.runId = runId;
        }
    }

    /** A new secret for endpoint {@code endpointId}'s gateway, in place of any an earlier gateway had. */
    String secretFor(String endpointId, String accountId, String region) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);
        gateways.put(endpointId, new Gateway(secret, accountId, region));
        return secret;
    }

    /** The gateway of endpoint {@code endpointId} that {@code secret} is the secret of, or null for none. */
    Gateway gateway(String endpointId, String secret) {
        Gateway gateway = gateways.get(endpointId);
        return gateway != null && secret != null && MessageDigest.isEqual(
                gateway.secret().getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))
                ? gateway : null;
    }

    /** The id of a new run of a kernel's container: its endpoint's and its own, with a count. */
    String runId(String endpointId, String kernelId) {
        return endpointId + "-" + kernelId + "-" + launches.incrementAndGet();
    }

    /**
     * Runs {@code job}, kernel {@code kernelId}'s container, on a thread of its own, its earlier one stopped; false,
     * running nothing, once the endpoint's gateway has ended ({@link #stopAll}), which takes its kernels under the
     * same lock.
     */
    boolean launch(String endpointId, String kernelId, SparkJobRunner.Job job) {
        Kernel kernel = new Kernel(job.runId());
        Kernel earlier;
        synchronized (this) {
            if (!gateways.containsKey(endpointId)) {
                return false;
            }
            earlier = kernels.computeIfAbsent(endpointId, id -> new HashMap<>()).put(kernelId, kernel);
        }
        if (earlier != null) {
            stop(earlier);
        }
        executor.execute(() -> {
            try {
                SparkJobRunner.Outcome outcome = runner.run(job, new Progress(kernel));
                if (outcome.failure() != null) {
                    LOG.warnv("The container of kernel {0} of endpoint {1} could not start: {2}", kernelId,
                            endpointId, outcome.failure());
                }
            } catch (RuntimeException e) {
                LOG.warnv(e, "The container of kernel {0} of endpoint {1} failed", kernelId, endpointId);
            } finally {
                synchronized (this) {
                    Map<String, Kernel> endpointKernels = kernels.get(endpointId);
                    if (endpointKernels != null && endpointKernels.remove(kernelId, kernel)
                            && endpointKernels.isEmpty()) {
                        kernels.remove(endpointId);
                    }
                }
            }
        });
        return true;
    }

    /** Stops kernel {@code kernelId}'s container, where it runs. */
    void stop(String endpointId, String kernelId) {
        Kernel kernel;
        synchronized (this) {
            Map<String, Kernel> endpointKernels = kernels.get(endpointId);
            kernel = endpointKernels != null ? endpointKernels.get(kernelId) : null;
        }
        if (kernel != null) {
            stop(kernel);
        }
    }

    /** Stops the containers of the endpoint's kernels, its gateway's secret going with them. */
    void stopAll(String endpointId) {
        List<Kernel> running;
        synchronized (this) {
            gateways.remove(endpointId);
            Map<String, Kernel> endpointKernels = kernels.get(endpointId);
            running = endpointKernels != null ? List.copyOf(endpointKernels.values()) : List.of();
        }
        running.forEach(this::stop);
    }

    /** Stops every kernel's container, the gateways' secrets going with them, as a reset ends every endpoint. */
    void reset() {
        List<Kernel> running;
        synchronized (this) {
            gateways.clear();
            running = kernels.values().stream().flatMap(endpointKernels -> endpointKernels.values().stream())
                    .toList();
        }
        running.forEach(this::stop);
    }

    /** {@link #reset}, and the threads that run the containers stop, as Floci does. */
    void shutdown() {
        reset();
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    private void stop(Kernel kernel) {
        kernel.stopped = true;
        runner.stop(kernel.runId);
    }

    // A kernel's container's run: cancelled once asked to stop.
    private record Progress(Kernel kernel) implements SparkJobRunner.Progress {

        @Override
        public void started() {
        }

        @Override
        public void timedOut() {
        }

        @Override
        public boolean cancelled() {
            return kernel.stopped;
        }
    }
}
