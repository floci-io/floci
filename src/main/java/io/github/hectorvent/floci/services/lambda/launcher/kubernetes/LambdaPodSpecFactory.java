package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerLauncher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the Pod manifest for one Lambda execution environment. Code cannot be
 * copied into a pod before it starts (no docker-cp equivalent), so an init
 * container downloads the deployment package (and layers) from Floci's S3 over
 * HTTP into an emptyDir mounted at /var/task before the runtime starts.
 */
@ApplicationScoped
public class LambdaPodSpecFactory {

    private static final Logger LOG = Logger.getLogger(LambdaPodSpecFactory.class);

    /** Label key: optional DNS-subdomain prefix (dot-separated valid segments), then a 63-char-max name. */
    private static final java.util.regex.Pattern LABEL_KEY =
            java.util.regex.Pattern.compile("([a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*/)?[a-zA-Z0-9]([-a-zA-Z0-9_.]{0,61}[a-zA-Z0-9])?");
    /** Label value: empty, or 63-char-max alphanumeric-bounded. */
    private static final java.util.regex.Pattern LABEL_VALUE =
            java.util.regex.Pattern.compile("([a-zA-Z0-9]([-a-zA-Z0-9_.]{0,61}[a-zA-Z0-9])?)?");

    static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    static final String MANAGED_BY_VALUE = "floci";
    static final String SERVICE_LABEL = "floci.io/service";
    static final String SERVICE_VALUE = "lambda";
    static final String FUNCTION_LABEL = "floci.io/function-name";

    private static final String TASK_DIR = "/var/task";
    private static final String OPT_DIR = "/opt";
    private static final String RUNTIME_DIR = "/var/runtime";
    private static final int MAX_NAME_LENGTH = 63;

    private final EmulatorConfig config;

    @Inject
    public LambdaPodSpecFactory(EmulatorConfig config) {
        this.config = config;
    }

    /**
     * @param podName          DNS-safe pod name (see {@link #podName})
     * @param image            resolved runtime image
     * @param env              {@code KEY=value} entries, same shape the Docker launcher builds
     * @param codeDownloadUrl  URL of the function's deployment zip; null for Image package type
     * @param layerDownloadUrls layer zips to unpack into /opt, in merge order
     * @param providedRuntime  whether the runtime is provided.* (bootstrap needs an exec bit)
     * @param handlerOrNull    function handler used as the runtime container arg (Zip functions)
     * @param imageConfig      Image-package-type entrypoint/command/workingdir; empty lists/null when unset
     * @param caConfigMapName  ConfigMap holding Floci's CA cert, mounted at /etc/floci-ca.crt when present
     */
    public Pod buildPod(String podName,
                        String functionName,
                        String image,
                        List<String> env,
                        String codeDownloadUrl,
                        List<String> layerDownloadUrls,
                        boolean providedRuntime,
                        String handlerOrNull,
                        ImageConfig imageConfig,
                        int memoryMb,
                        Optional<String> caConfigMapName) {
        var hasCode = codeDownloadUrl != null;
        var hasLayers = !layerDownloadUrls.isEmpty();

        var volumes = new ArrayList<Volume>();
        var runtimeMounts = new ArrayList<VolumeMount>();
        if (hasCode) {
            volumes.add(new VolumeBuilder().withName("task").withNewEmptyDir().endEmptyDir().build());
            runtimeMounts.add(mount("task", TASK_DIR));
        }
        if (hasLayers) {
            volumes.add(new VolumeBuilder().withName("opt").withNewEmptyDir().endEmptyDir().build());
            runtimeMounts.add(mount("opt", OPT_DIR));
        }
        // The provided.* base images exec /var/runtime/bootstrap with no /var/task
        // fallback, and their /var/runtime ships empty, so masking it with an emptyDir
        // that the init container copies bootstrap into is safe and required.
        var needsRuntimeDir = providedRuntime && hasCode;
        if (needsRuntimeDir) {
            volumes.add(new VolumeBuilder().withName("runtime").withNewEmptyDir().endEmptyDir().build());
            runtimeMounts.add(mount("runtime", RUNTIME_DIR));
        }
        caConfigMapName.ifPresent(cm -> {
            volumes.add(new VolumeBuilder().withName("floci-ca")
                    .withNewConfigMap().withName(cm).endConfigMap().build());
            runtimeMounts.add(new VolumeMountBuilder()
                    .withName("floci-ca")
                    .withMountPath(ContainerLauncher.FLOCI_CA_CONTAINER_PATH)
                    .withSubPath(KubernetesPodLauncher.CA_CONFIG_MAP_KEY)
                    .withReadOnly(true)
                    .build());
        });

        var runtime = new ContainerBuilder()
                .withName("runtime")
                .withImage(image)
                // Explicit IfNotPresent: the default for :latest/untagged is Always,
                // which breaks images pre-loaded onto nodes (kind load docker-image).
                .withImagePullPolicy("IfNotPresent")
                .withEnv(toEnvVars(env))
                .withVolumeMounts(runtimeMounts)
                .withNewResources()
                .addToRequests("memory", new Quantity(memoryMb + "Mi"))
                .addToLimits("memory", new Quantity(memoryMb + "Mi"))
                .endResources();

        if (imageConfig != null && !imageConfig.entryPoint().isEmpty()) {
            runtime.withCommand(escapeDollars(imageConfig.entryPoint()));
        }
        if (imageConfig != null && !imageConfig.command().isEmpty()) {
            runtime.withArgs(escapeDollars(imageConfig.command()));
        } else if (handlerOrNull != null && !handlerOrNull.isBlank()) {
            runtime.withArgs(escapeDollar(handlerOrNull));
        }
        if (imageConfig != null && imageConfig.workingDirectory() != null
                && !imageConfig.workingDirectory().isBlank()) {
            runtime.withWorkingDir(imageConfig.workingDirectory());
        }

        var initContainers = new ArrayList<Container>();
        if (hasCode) {
            var initMounts = new ArrayList<VolumeMount>();
            initMounts.add(mount("task", TASK_DIR));
            if (hasLayers) {
                initMounts.add(mount("opt", OPT_DIR));
            }
            if (needsRuntimeDir) {
                initMounts.add(mount("runtime", RUNTIME_DIR));
            }
            initContainers.add(new ContainerBuilder()
                    .withName("code-download")
                    .withImage(config.services().lambda().kubernetes().initImage())
                    .withImagePullPolicy("IfNotPresent")
                    .withCommand("sh", "-c", initScript(codeDownloadUrl, layerDownloadUrls, providedRuntime))
                    .withVolumeMounts(initMounts)
                    .build());
        }

        return new PodBuilder()
                .withNewMetadata()
                .withName(podName)
                .withLabels(podLabels(functionName))
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withTerminationGracePeriodSeconds(5L)
                .withInitContainers(initContainers)
                .withContainers(runtime.build())
                .withVolumes(volumes)
                .endSpec()
                .build();
    }

    /** Entrypoint/command/workingdir of an Image-package-type function. */
    public record ImageConfig(List<String> entryPoint, List<String> command, String workingDirectory) {
        public ImageConfig {
            entryPoint = entryPoint == null ? List.of() : entryPoint;
            command = command == null ? List.of() : command;
        }
    }

    String initScript(String codeDownloadUrl, List<String> layerDownloadUrls, boolean providedRuntime) {
        // Download URLs are always plain HTTP (see KubernetesFlociAddressResolver
        // .downloadBaseUrl): busybox wget's built-in TLS cannot handshake with Floci.
        var wget = "wget -q";
        var script = new StringBuilder("set -e\n");
        script.append(wget).append(" -O /tmp/code.zip '").append(codeDownloadUrl).append("'\n");
        script.append("unzip -oq /tmp/code.zip -d ").append(TASK_DIR).append("\n");
        for (var i = 0; i < layerDownloadUrls.size(); i++) {
            script.append(wget).append(" -O /tmp/layer").append(i).append(".zip '")
                    .append(layerDownloadUrls.get(i)).append("'\n");
            script.append("unzip -oq /tmp/layer").append(i).append(".zip -d ").append(OPT_DIR).append("\n");
        }
        script.append("rm -f /tmp/code.zip /tmp/layer*.zip\n");
        if (providedRuntime) {
            // The provided.* entrypoint execs /var/runtime/bootstrap, mirroring the
            // docker path's tar-copy into RUNTIME_DIR. busybox unzip drops unix mode
            // bits, so the exec bit must be restored explicitly.
            script.append("if [ -f ").append(TASK_DIR).append("/bootstrap ]; then cp ")
                    .append(TASK_DIR).append("/bootstrap ").append(RUNTIME_DIR)
                    .append("/bootstrap && chmod +x ").append(RUNTIME_DIR).append("/bootstrap; fi\n");
        }
        return script.toString();
    }

    Map<String, String> podLabels(String functionName) {
        var labels = new LinkedHashMap<String, String>();
        config.services().lambda().kubernetes().labels().orElse(List.of()).forEach(entry -> {
            var eq = entry.indexOf('=');
            var key = eq > 0 ? entry.substring(0, eq).trim() : "";
            var value = eq > 0 ? entry.substring(eq + 1).trim() : "";
            // Invalid entries are dropped, not sanitized: a silently rewritten label
            // would no longer match the NetworkPolicy/selector the user wrote it for,
            // and the API server would reject the pod at cold start otherwise.
            if (LABEL_KEY.matcher(key).matches() && LABEL_VALUE.matcher(value).matches()) {
                labels.put(key, value);
            } else {
                LOG.warnv("Ignoring invalid Lambda pod label entry ''{0}'' "
                        + "(expected key=value with Kubernetes label syntax)", entry);
            }
        });
        // Managed labels go last so user entries can never overwrite them — the
        // orphan sweep selects on these, and an overwritten label leaks pods forever.
        labels.put(MANAGED_BY_LABEL, MANAGED_BY_VALUE);
        labels.put(SERVICE_LABEL, SERVICE_VALUE);
        labels.put(FUNCTION_LABEL, sanitizeLabelValue(functionName));
        return labels;
    }

    /** Selector matching every pod this Floci instance's Lambda executor manages. */
    static Map<String, String> managedPodSelector() {
        return Map.of(MANAGED_BY_LABEL, MANAGED_BY_VALUE, SERVICE_LABEL, SERVICE_VALUE);
    }

    /**
     * DNS-1123 pod name: lowercase alphanumerics and dashes, max 63 chars. The random
     * suffix stays intact; the function name is truncated to fit.
     */
    static String podName(String functionName, String shortId) {
        var prefix = "floci-lambda-";
        var sanitized = functionName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        var budget = MAX_NAME_LENGTH - prefix.length() - shortId.length() - 1;
        if (sanitized.length() > budget) {
            sanitized = sanitized.substring(0, budget);
        }
        sanitized = sanitized.replaceAll("^-+|-+$", "");
        if (sanitized.isEmpty()) {
            sanitized = "fn";
        }
        return prefix + sanitized + "-" + shortId;
    }

    /** Label values allow [a-zA-Z0-9-_.], max 63 chars, alphanumeric at both ends. */
    static String sanitizeLabelValue(String value) {
        var sanitized = value.replaceAll("[^a-zA-Z0-9-_.]", "-");
        if (sanitized.length() > MAX_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_NAME_LENGTH);
        }
        sanitized = sanitized.replaceAll("^[^a-zA-Z0-9]+", "").replaceAll("[^a-zA-Z0-9]+$", "");
        return sanitized;
    }

    private static VolumeMount mount(String name, String path) {
        return new VolumeMountBuilder().withName(name).withMountPath(path).build();
    }

    private static List<String> escapeDollars(List<String> values) {
        return values.stream().map(LambdaPodSpecFactory::escapeDollar).toList();
    }

    private static String escapeDollar(String value) {
        // Kubernetes expands $(VAR) and collapses $$ in command/args exactly as in env
        // values, so escape $ to deliver them byte-for-byte like the docker executor.
        return value.replace("$", "$$");
    }

    private static List<EnvVar> toEnvVars(List<String> env) {
        var vars = new ArrayList<EnvVar>(env.size());
        for (var entry : env) {
            var eq = entry.indexOf('=');
            var key = eq >= 0 ? entry.substring(0, eq) : entry;
            var value = eq >= 0 ? entry.substring(eq + 1) : "";
            // The API server rejects a pod whose env var has an empty name.
            if (key.isEmpty()) {
                LOG.warnv("Skipping env entry with an empty name in the pod spec: ''{0}''", entry);
                continue;
            }
            // Kubernetes expands $(VAR) in env values and collapses $$ to $.
            // Escaping every $ delivers values byte-for-byte like the docker executor.
            vars.add(new EnvVar(key, value.replace("$", "$$"), null));
        }
        return vars;
    }
}
