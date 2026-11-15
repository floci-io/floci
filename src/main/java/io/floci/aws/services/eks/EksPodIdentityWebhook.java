package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.services.eks.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Admission logic behind the EKS Pod Identity mutating webhook.
 *
 * <p>Real EKS mutates a pod at admission when its service account has a pod identity association
 * and the {@code eks-pod-identity-agent} addon is installed. The mutation projects a service
 * account token with the pod identity audience and sets the two container credentials environment
 * variables the AWS SDKs read, so the SDK credential chain reaches the container credentials
 * provider instead of falling through. Floci reproduces exactly that mutation, and also points the
 * SDK at Floci with {@code AWS_ENDPOINT_URL} and {@code AWS_ENDPOINT_URL_STS}, as it does for every
 * container it launches itself: otherwise the pod's calls, signed with Floci credentials, go to AWS.
 *
 * <p>Nothing answers {@link #CREDENTIALS_FULL_URI} yet: this class injects the plumbing only, so an
 * injected pod still cannot obtain credentials.
 *
 * <p>Every path here admits the pod. A cluster that is gone, an addon that is not installed, a
 * malformed review and an internal error all return {@code allowed: true} with no patch, which is
 * what {@code failurePolicy: Ignore} promises on the k3s side.
 */
@ApplicationScoped
public class EksPodIdentityWebhook {

    private static final Logger LOG = Logger.getLogger(EksPodIdentityWebhook.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Real EKS only injects when the pod identity agent is installed on the cluster. */
    static final String POD_IDENTITY_ADDON = "eks-pod-identity-agent";

    static final String TOKEN_VOLUME_NAME = "eks-pod-identity-token";
    static final String TOKEN_AUDIENCE = "pods.eks.amazonaws.com"; // partition-literal: pod-identity token audience; no source outside the commercial partition (P9)
    static final String TOKEN_MOUNT_PATH = "/var/run/secrets/pods.eks.amazonaws.com/serviceaccount"; // partition-literal: the projected-token mount path the EKS agent hardcodes
    static final String TOKEN_FILE_NAME = "eks-pod-identity-token";
    static final String TOKEN_FILE_PATH = TOKEN_MOUNT_PATH + "/" + TOKEN_FILE_NAME;
    static final int TOKEN_EXPIRATION_SECONDS = 86400;
    static final int TOKEN_DEFAULT_MODE = 420;

    static final String ENV_CREDENTIALS_FULL_URI = "AWS_CONTAINER_CREDENTIALS_FULL_URI";
    static final String ENV_AUTHORIZATION_TOKEN_FILE = "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE";
    static final String CREDENTIALS_FULL_URI = "http://169.254.170.23/v1/credentials";
    static final String ENV_ENDPOINT_URL = "AWS_ENDPOINT_URL";
    /**
     * Set beside the global variable because the AWS SDK for Go v2 builds the STS client of its web
     * identity provider before it reads {@code AWS_ENDPOINT_URL}.
     */
    static final String ENV_ENDPOINT_URL_STS = "AWS_ENDPOINT_URL_STS";

    private static final String DEFAULT_API_VERSION = "admission.k8s.io/v1";
    private static final String DEFAULT_SERVICE_ACCOUNT = "default";

    private final EksService eks;
    private final EksAddonService addons;
    private final EksPodIdentityAssociationService associations;
    private final Supplier<String> flociEndpoint;

    /** The endpoint is the Docker host address a launched container gets, when a pod can reach it. */
    @Inject
    public EksPodIdentityWebhook(EksService eks, EksAddonService addons,
                                 EksPodIdentityAssociationService associations,
                                 ContainerReachableEndpoint endpoint) {
        this(eks, addons, associations, () -> podReachableEndpoint(endpoint.baseUrl(false)));
    }

    EksPodIdentityWebhook(EksService eks, EksAddonService addons,
                          EksPodIdentityAssociationService associations, Supplier<String> flociEndpoint) {
        this.eks = eks;
        this.addons = addons;
        this.associations = associations;
        this.flociEndpoint = flociEndpoint;
    }

    /**
     * Reviews one pod CREATE and returns the {@code AdmissionReview} to hand back to the API server.
     * The account comes from the URL because the k3s API server calls this anonymously: without it
     * the cluster would only ever be looked up in the default account.
     */
    public Map<String, Object> review(String clusterName, String accountId, Map<String, Object> admissionReview) {
        return review(clusterName, accountId, null, admissionReview);
    }

    /**
     * As {@link #review(String, String, Map)}, for the cluster in {@code region}: cluster names are
     * unique only within a Region. A null Region, from a route registered before the Region was
     * part of it, resolves the cluster created before Regional names that registered it.
     */
    public Map<String, Object> review(String clusterName, String accountId, String region,
                                      Map<String, Object> admissionReview) {
        String apiVersion = admissionReview != null && admissionReview.get("apiVersion") instanceof String version
                ? version : DEFAULT_API_VERSION;
        Map<String, Object> request = admissionReview == null ? null : asMap(admissionReview.get("request"));
        String uid = request != null && request.get("uid") instanceof String value ? value : "";
        try {
            return admissionReview(apiVersion, uid, decide(clusterName, accountId, region, request));
        } catch (RuntimeException e) {
            LOG.warnv("EKS pod-identity-webhook: admitting pod unchanged for cluster {0} after an "
                    + "internal error: {1}", clusterName, e.toString());
            return admissionReview(apiVersion, uid, List.of());
        }
    }

    private List<Map<String, Object>> decide(String clusterName, String accountId, String region,
                                             Map<String, Object> request) {
        if (request == null) {
            return List.of();
        }
        Map<String, Object> pod = asMap(request.get("object"));
        if (pod == null) {
            return List.of();
        }
        String namespace = namespaceOf(request, pod);
        if (namespace == null) {
            return List.of();
        }
        // A route without a Region was registered by a cluster created before cluster names became
        // Regional, so that cluster answers it, not a same-named one created since.
        Optional<Cluster> cluster = region == null
                ? eks.findPreRegionalCluster(accountId, clusterName)
                : eks.findAuthenticationCluster(accountId, region, clusterName);
        if (cluster.isEmpty()) {
            return List.of();
        }
        return RequestScopes.callAs(accountId, () -> {
            if (!podIdentityAgentInstalled(cluster.get())
                    || !hasAssociation(cluster.get(), namespace, serviceAccountOf(pod))) {
                return List.of();
            }
            return buildPatch(pod, flociEndpoint.get());
        });
    }

    /**
     * A pod resolves names through CoreDNS, which never sees the cluster container's
     * {@code /etc/hosts}, where {@code host.docker.internal} lives on Linux. Floci's DNS server does
     * not reach it either: on a custom Docker network the container's resolver is {@code 127.0.0.11},
     * which k3s rejects as loopback and replaces with a public resolver, so Floci's hostname resolves
     * to {@code 127.0.0.1} inside the pod. Only an IP address is reachable, and that is what
     * {@code baseUrl(false)} returns whenever Floci runs in a container. No endpoint is injected
     * otherwise, which leaves the SDK default.
     */
    static String podReachableEndpoint(String baseUrl) {
        if (EksClusterManager.isIpv4Literal(URI.create(baseUrl).getHost())) {
            return baseUrl;
        }
        LOG.debugv("EKS pod-identity-webhook: pods cannot resolve {0}, injecting no endpoint", baseUrl);
        return null;
    }

    private boolean podIdentityAgentInstalled(Cluster cluster) {
        try {
            addons.describe(cluster, POD_IDENTITY_ADDON);
            return true;
        } catch (AwsException e) {
            LOG.debugv("EKS pod-identity-webhook: cluster {0} has no {1} addon, admitting pods unchanged ({2})",
                    cluster.getName(), POD_IDENTITY_ADDON, e.getMessage());
            return false;
        }
    }

    private boolean hasAssociation(Cluster cluster, String namespace, String serviceAccount) {
        try {
            return !associations.list(cluster, namespace, serviceAccount, 1, null).associations().isEmpty();
        } catch (AwsException e) {
            LOG.debugv("EKS pod-identity-webhook: could not list associations for {0}/{1} on cluster {2} ({3})",
                    namespace, serviceAccount, cluster.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * The JSONPatch that turns a pod into an injected pod: one projected token volume, then a mount,
     * the two credentials environment variables and the two endpoint variables on every init
     * container and container. An entry the pod already carries under the same name is left alone,
     * matching the real webhook, which never overrides a value the workload set itself. A container
     * that sets {@code AWS_ENDPOINT_URL} gets no {@code AWS_ENDPOINT_URL_STS} either, since the
     * service-specific variable would take its STS calls away from the endpoint it chose.
     */
    static List<Map<String, Object>> buildPatch(Map<String, Object> pod, String endpointUrl) {
        Map<String, Object> spec = asMap(pod.get("spec"));
        if (spec == null) {
            return List.of();
        }
        List<Map<String, Object>> patch = new ArrayList<>();
        List<Object> volumes = asList(spec.get("volumes"));
        if (volumes == null) {
            patch.add(operation("/spec/volumes", List.of(tokenVolume())));
        } else if (!containsNamed(volumes, TOKEN_VOLUME_NAME)) {
            patch.add(operation("/spec/volumes/-", tokenVolume()));
        }
        List<Map<String, Object>> injectedEnv = injectedEnv(endpointUrl);
        patchContainers(patch, spec, "initContainers", injectedEnv);
        patchContainers(patch, spec, "containers", injectedEnv);
        return patch;
    }

    private static void patchContainers(List<Map<String, Object>> patch, Map<String, Object> spec, String field,
                                        List<Map<String, Object>> injectedEnv) {
        List<Object> containers = asList(spec.get(field));
        if (containers == null) {
            return;
        }
        for (int index = 0; index < containers.size(); index++) {
            Map<String, Object> container = asMap(containers.get(index));
            if (container == null) {
                continue;
            }
            String path = "/spec/" + field + "/" + index;
            List<Object> mounts = asList(container.get("volumeMounts"));
            if (mounts == null) {
                patch.add(operation(path + "/volumeMounts", List.of(tokenMount())));
            } else if (!containsNamed(mounts, TOKEN_VOLUME_NAME)) {
                patch.add(operation(path + "/volumeMounts/-", tokenMount()));
            }
            List<Object> env = asList(container.get("env"));
            List<Object> envFrom = asList(container.get("envFrom"));
            boolean mayDefineEndpoint = envFrom != null && !envFrom.isEmpty();
            boolean setsGlobalEndpoint = env != null && containsNamed(env, ENV_ENDPOINT_URL);
            List<Map<String, Object>> missing = new ArrayList<>();
            for (Map<String, Object> variable : injectedEnv) {
                String name = String.valueOf(variable.get("name"));
                if (mayDefineEndpoint && isEndpointVariable(name)) {
                    continue;
                }
                if (setsGlobalEndpoint && ENV_ENDPOINT_URL_STS.equals(name)) {
                    continue;
                }
                if (env == null || !containsNamed(env, name)) {
                    missing.add(variable);
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            if (env == null) {
                patch.add(operation(path + "/env", missing));
            } else {
                for (Map<String, Object> variable : missing) {
                    patch.add(operation(path + "/env/-", variable));
                }
            }
        }
    }

    static Map<String, Object> tokenVolume() {
        return Map.of(
                "name", TOKEN_VOLUME_NAME,
                "projected", Map.of(
                        "defaultMode", TOKEN_DEFAULT_MODE,
                        "sources", List.of(Map.of("serviceAccountToken", Map.of(
                                "audience", TOKEN_AUDIENCE,
                                "expirationSeconds", TOKEN_EXPIRATION_SECONDS,
                                "path", TOKEN_FILE_NAME)))));
    }

    static Map<String, Object> tokenMount() {
        return Map.of("name", TOKEN_VOLUME_NAME, "mountPath", TOKEN_MOUNT_PATH, "readOnly", true);
    }

    static List<Map<String, Object>> credentialsEnv() {
        return List.of(
                Map.of("name", ENV_CREDENTIALS_FULL_URI, "value", CREDENTIALS_FULL_URI),
                Map.of("name", ENV_AUTHORIZATION_TOKEN_FILE, "value", TOKEN_FILE_PATH));
    }

    /**
     * The values {@code envFrom} brings in are not known at admission, and an {@code env} entry would
     * take precedence over them, so a container that has any gets no endpoint variable.
     */
    private static boolean isEndpointVariable(String name) {
        return ENV_ENDPOINT_URL.equals(name) || ENV_ENDPOINT_URL_STS.equals(name);
    }

    static List<Map<String, Object>> injectedEnv(String endpointUrl) {
        List<Map<String, Object>> env = new ArrayList<>(credentialsEnv());
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            env.add(Map.of("name", ENV_ENDPOINT_URL, "value", endpointUrl));
            env.add(Map.of("name", ENV_ENDPOINT_URL_STS, "value", endpointUrl));
        }
        return env;
    }

    private static Map<String, Object> operation(String path, Object value) {
        return Map.of("op", "add", "path", path, "value", value);
    }

    private static Map<String, Object> admissionReview(String apiVersion, String uid,
                                                       List<Map<String, Object>> patch) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("uid", uid);
        response.put("allowed", true);
        if (!patch.isEmpty()) {
            response.put("patchType", "JSONPatch");
            response.put("patch", encodePatch(patch));
        }
        return Map.of("apiVersion", apiVersion, "kind", "AdmissionReview", "response", response);
    }

    private static String encodePatch(List<Map<String, Object>> patch) {
        try {
            return Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(patch));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the pod identity JSONPatch", e);
        }
    }

    /**
     * The namespace the admission decision is made against: the API server always sets it on the
     * request, and the pod's own metadata is the fallback for a hand-made review.
     */
    private static String namespaceOf(Map<String, Object> request, Map<String, Object> pod) {
        if (request.get("namespace") instanceof String namespace && !namespace.isBlank()) {
            return namespace;
        }
        Map<String, Object> metadata = asMap(pod.get("metadata"));
        if (metadata != null && metadata.get("namespace") instanceof String namespace && !namespace.isBlank()) {
            return namespace;
        }
        return null;
    }

    /** Kubernetes defaults an omitted service account to {@code default} before webhooks run. */
    private static String serviceAccountOf(Map<String, Object> pod) {
        Map<String, Object> spec = asMap(pod.get("spec"));
        if (spec != null && spec.get("serviceAccountName") instanceof String name && !name.isBlank()) {
            return name;
        }
        return DEFAULT_SERVICE_ACCOUNT;
    }

    private static boolean containsNamed(List<Object> entries, String name) {
        for (Object entry : entries) {
            Map<String, Object> map = asMap(entry);
            if (map != null && name.equals(map.get("name"))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }
}
