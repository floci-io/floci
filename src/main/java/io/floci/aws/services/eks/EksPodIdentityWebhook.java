package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.services.eks.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Admission logic behind the EKS Pod Identity mutating webhook, which, as the Amazon EKS pod identity webhook does,
 * makes both mutations: EKS Pod Identity's and IAM roles for service accounts'.
 *
 * <p>Real EKS mutates a pod at admission when its service account has a pod identity association
 * and the {@code eks-pod-identity-agent} addon is installed. The mutation projects a service
 * account token with the pod identity audience and sets the two container credentials environment
 * variables the AWS SDKs read, so the SDK credential chain reaches the container credentials
 * provider instead of falling through. Floci reproduces exactly that mutation, and also points the
 * SDK at Floci with {@code AWS_ENDPOINT_URL} and {@code AWS_ENDPOINT_URL_STS}, as it does for every
 * container it launches itself: otherwise the pod's calls, signed with Floci credentials, go to AWS.
 *
 * <p>A pod that does not get that mutation gets the IAM roles for service accounts one when its service account
 * carries {@value #ANNOTATION_ROLE_ARN}: a projected token for STS and the web identity environment variables, read
 * from the service account's annotations and the pod's, as the upstream webhook reads them. The webhook is handed
 * only the pod, so the service account is read from the cluster; the upstream webhook keeps a cache of them instead.
 * Pod Identity takes precedence, as the upstream webhook's container credentials do, and the two are never injected
 * together.
 *
 * <p>This class injects the plumbing only. {@link #CREDENTIALS_FULL_URI} answers while the addon is
 * installed: {@link EksClusterManager} forwards its address in the node to
 * {@link EksPodIdentityCredentialsController}, which hands the pod its association's credentials.
 *
 * <p>Every path here admits the pod. A cluster that is gone, an addon that is not installed, a
 * malformed review and an internal error all return {@code allowed: true} with no patch, which is
 * what {@code failurePolicy: Ignore} promises on the k3s side.
 */
@ApplicationScoped
public class EksPodIdentityWebhook {

    private static final Logger LOG = Logger.getLogger(EksPodIdentityWebhook.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

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

    /** The service account annotations and pod annotations of IAM roles for service accounts. */
    static final String ANNOTATION_PREFIX = "eks.amazonaws.com/"; // partition-literal: a Kubernetes annotation prefix, not a host; EKS uses it in every partition
    static final String ANNOTATION_ROLE_ARN = ANNOTATION_PREFIX + "role-arn";
    static final String ANNOTATION_AUDIENCE = ANNOTATION_PREFIX + "audience";
    static final String ANNOTATION_STS_REGIONAL_ENDPOINTS = ANNOTATION_PREFIX + "sts-regional-endpoints";
    static final String ANNOTATION_TOKEN_EXPIRATION = ANNOTATION_PREFIX + "token-expiration";
    static final String ANNOTATION_SKIP_CONTAINERS = ANNOTATION_PREFIX + "skip-containers";

    static final String IRSA_VOLUME_NAME = "aws-iam-token";
    static final String IRSA_MOUNT_PATH = "/var/run/secrets/" + ANNOTATION_PREFIX + "serviceaccount";
    static final String IRSA_TOKEN_FILE_NAME = "token";
    static final String IRSA_TOKEN_FILE = IRSA_MOUNT_PATH + "/" + IRSA_TOKEN_FILE_NAME;
    static final long IRSA_DEFAULT_TOKEN_EXPIRATION = 86400;
    /** The shortest service account token the API server issues, which the upstream webhook raises shorter ones to. */
    static final long IRSA_MIN_TOKEN_EXPIRATION = 600;

    static final String ENV_ROLE_ARN = "AWS_ROLE_ARN";
    static final String ENV_WEB_IDENTITY_TOKEN_FILE = "AWS_WEB_IDENTITY_TOKEN_FILE";
    static final String ENV_STS_REGIONAL_ENDPOINTS = "AWS_STS_REGIONAL_ENDPOINTS";
    static final String ENV_REGION = "AWS_REGION";
    static final String ENV_DEFAULT_REGION = "AWS_DEFAULT_REGION";

    /** How long the service account lookup may take, inside the webhook's own three-second timeout. */
    static final int SERVICE_ACCOUNT_LOOKUP_SECONDS = 2;
    /**
     * How long a service account read is reused, so a burst of pods, and the cluster's own pods of service accounts
     * with no role, cost one read each: the upstream webhook's informer cache is current within moments instead.
     */
    static final Duration SERVICE_ACCOUNT_CACHE_TTL = Duration.ofSeconds(10);

    private static final String DEFAULT_API_VERSION = "admission.k8s.io/v1";
    private static final String DEFAULT_SERVICE_ACCOUNT = "default";

    /** Reads a service account of a cluster. */
    @FunctionalInterface
    interface ServiceAccountReader {
        EksClusterManager.ServiceAccountRead read(Cluster cluster, String namespace, String name);
    }

    private record ServiceAccountKey(String cluster, String namespace, String name) {}

    /** A read of a service account, in flight or done, which pods created before {@code expiresAt} reuse. */
    private record CachedRead(Instant expiresAt, CompletableFuture<EksClusterManager.ServiceAccountRead> read) {}

    /** What the service account's and the pod's annotations ask of the IAM roles for service accounts mutation. */
    record WebIdentity(String roleArn, String audience, long tokenExpiration, boolean regionalSts) {}

    private final EksService eks;
    private final EksAddonService addons;
    private final EksPodIdentityAssociationService associations;
    private final Supplier<String> flociEndpoint;
    private final ServiceAccountReader serviceAccounts;
    private final Function<Cluster, String> regionOf;
    private final Clock clock;
    private final Duration lookupTimeout;
    private final Map<ServiceAccountKey, CachedRead> serviceAccountReads = new ConcurrentHashMap<>();
    // A read runs apart from the admission request, so a Docker call that hangs before kubectl even starts still
    // leaves the webhook answering within lookupTimeout.
    private final ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor();

    /** The endpoint is the Docker host address a launched container gets, when a pod can reach it. */
    @Inject
    public EksPodIdentityWebhook(EksService eks, EksAddonService addons,
                                 EksPodIdentityAssociationService associations,
                                 ContainerReachableEndpoint endpoint, EksClusterManager clusterManager) {
        this(eks, addons, associations, () -> podReachableEndpoint(endpoint.baseUrl(false)),
                (cluster, namespace, name) -> clusterManager.serviceAccount(cluster, namespace, name,
                        SERVICE_ACCOUNT_LOOKUP_SECONDS),
                clusterManager::clusterRegion, Clock.systemUTC(), Duration.ofSeconds(SERVICE_ACCOUNT_LOOKUP_SECONDS));
    }

    EksPodIdentityWebhook(EksService eks, EksAddonService addons,
                          EksPodIdentityAssociationService associations, Supplier<String> flociEndpoint,
                          ServiceAccountReader serviceAccounts, Function<Cluster, String> regionOf, Clock clock,
                          Duration lookupTimeout) {
        this.eks = eks;
        this.addons = addons;
        this.associations = associations;
        this.flociEndpoint = flociEndpoint;
        this.serviceAccounts = serviceAccounts;
        this.regionOf = regionOf;
        this.clock = clock;
        this.lookupTimeout = lookupTimeout;
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
        String serviceAccount = serviceAccountOf(pod);
        return RequestScopes.callAs(accountId, () -> {
            if (podIdentityAgentInstalled(cluster.get()) && hasAssociation(cluster.get(), namespace, serviceAccount)) {
                return buildPatch(pod, flociEndpoint.get());
            }
            Optional<WebIdentity> webIdentity = webIdentity(cluster.get(), namespace, serviceAccount, pod);
            return webIdentity.isEmpty() ? List.<Map<String, Object>>of()
                    : buildWebIdentityPatch(pod, webIdentity.get(), regionOf.apply(cluster.get()), flociEndpoint.get());
        });
    }

    /**
     * The IAM roles for service accounts mutation the pod asks for, from its service account's annotations and its
     * own; empty when the service account carries no role ARN or cannot be read. A token for an audience other than
     * {@code sts.amazonaws.com}, which Floci's STS takes no other audience for a cluster's tokens in place of, could
     * never be exchanged, so its pod is left as it is, with a warning.
     */
    private Optional<WebIdentity> webIdentity(Cluster cluster, String namespace, String serviceAccount,
                                              Map<String, Object> pod) {
        String json = readServiceAccount(cluster, namespace, serviceAccount);
        Optional<WebIdentity> identity = webIdentity(json == null ? Map.of() : annotationsOf(json), annotationsOf(pod));
        if (identity.isPresent() && !EksOidcService.STS_AUDIENCE.equals(identity.get().audience())) {
            LOG.warnv("EKS pod-identity-webhook: service account {0}/{1} of cluster {2} asks for audience {3}, which "
                    + "Floci''s STS does not exchange a cluster''s tokens for, so its pods get no IAM role injected",
                    namespace, serviceAccount, cluster.getName(), identity.get().audience());
            return Optional.empty();
        }
        return identity;
    }

    /**
     * The service account as the cluster has it, null for none, read at most once per
     * {@link #SERVICE_ACCOUNT_CACHE_TTL}: pods created meanwhile, a read still in flight included, share it. A read
     * that fails is dropped from the cache once it ends, so the next pod reads again, and one that outlasts
     * {@code lookupTimeout} leaves this pod unchanged while it goes on for later ones.
     */
    private String readServiceAccount(Cluster cluster, String namespace, String name) {
        ServiceAccountKey key = new ServiceAccountKey(cluster.getArn() != null ? cluster.getArn() : cluster.getName(),
                namespace, name);
        Instant now = clock.instant();
        serviceAccountReads.values().removeIf(read -> !read.expiresAt().isAfter(now) && read.read().isDone());
        CachedRead[] started = new CachedRead[1];
        CachedRead cached = serviceAccountReads.compute(key, (ignored, existing) -> {
            if (existing != null && existing.expiresAt().isAfter(now)) {
                return existing;
            }
            started[0] = new CachedRead(now.plus(SERVICE_ACCOUNT_CACHE_TTL),
                    CompletableFuture.supplyAsync(() -> serviceAccounts.read(cluster, namespace, name), lookups));
            return started[0];
        });
        if (cached == started[0]) {
            cached.read().whenComplete((read, failure) -> {
                if (failure != null || !read.succeeded()) {
                    serviceAccountReads.remove(key, cached);
                }
            });
        }
        try {
            EksClusterManager.ServiceAccountRead read = cached.read()
                    .get(lookupTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return read.json();
        } catch (TimeoutException e) {
            LOG.warnv("EKS pod-identity-webhook: reading service account {0}/{1} of cluster {2} took over {3} ms, "
                    + "so this pod gets no IAM role injected", namespace, name, cluster.getName(),
                    lookupTimeout.toMillis());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            LOG.warnv(e.getCause(), "EKS pod-identity-webhook: could not read service account {0}/{1} of cluster {2}",
                    namespace, name, cluster.getName());
            return null;
        }
    }

    /**
     * Reads the annotations as the upstream webhook does. The audience defaults to {@code sts.amazonaws.com}. The
     * token lifetime is the service account's, then the pod's, in seconds, 86400 by default, a value that is not a
     * whole number leaving the one before, and a shorter one than 600 raised to 600. Regional STS endpoints are on
     * unless the service account turns them off, as EKS clusters default to them, and a value that is not a boolean
     * leaves the default.
     */
    static Optional<WebIdentity> webIdentity(Map<String, String> serviceAccount, Map<String, String> pod) {
        String roleArn = serviceAccount.get(ANNOTATION_ROLE_ARN);
        if (roleArn == null || roleArn.isBlank()) {
            return Optional.empty();
        }
        String audience = serviceAccount.get(ANNOTATION_AUDIENCE);
        long expiration = tokenExpiration(pod.get(ANNOTATION_TOKEN_EXPIRATION),
                tokenExpiration(serviceAccount.get(ANNOTATION_TOKEN_EXPIRATION), IRSA_DEFAULT_TOKEN_EXPIRATION));
        Boolean regional = parseBoolean(serviceAccount.get(ANNOTATION_STS_REGIONAL_ENDPOINTS));
        return Optional.of(new WebIdentity(roleArn, audience == null || audience.isBlank()
                ? EksOidcService.STS_AUDIENCE : audience, expiration, regional == null || regional));
    }

    private static long tokenExpiration(String value, long otherwise) {
        if (value == null) {
            return otherwise;
        }
        try {
            return Math.max(Long.parseLong(value), IRSA_MIN_TOKEN_EXPIRATION);
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    /** The values Go's {@code strconv.ParseBool} takes, which the upstream webhook reads booleans with. */
    private static Boolean parseBoolean(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "1", "t", "T", "TRUE", "true", "True" -> Boolean.TRUE;
            case "0", "f", "F", "FALSE", "false", "False" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static Map<String, String> annotationsOf(String serviceAccountJson) {
        try {
            return annotationsOf(MAPPER.readValue(serviceAccountJson, MAP_TYPE));
        } catch (JsonProcessingException e) {
            LOG.warnv(e, "EKS pod-identity-webhook: could not parse a service account, so its pods get no IAM role "
                    + "injected");
            return Map.of();
        }
    }

    private static Map<String, String> annotationsOf(Map<String, Object> object) {
        Map<String, Object> metadata = asMap(object.get("metadata"));
        Map<String, Object> annotations = metadata == null ? null : asMap(metadata.get("annotations"));
        Map<String, String> values = new LinkedHashMap<>();
        if (annotations != null) {
            annotations.forEach((key, value) -> {
                if (value instanceof String text) {
                    values.put(key, text);
                }
            });
        }
        return values;
    }

    /**
     * A pod resolves names through CoreDNS, which never sees the cluster container's
     * {@code /etc/hosts}, where {@code host.docker.internal} lives on Linux. Floci's hostname resolves
     * in a pod only when the cluster hands its pods Floci's embedded DNS server: without it, a custom
     * Docker network's resolver is {@code 127.0.0.11}, which k3s rejects as loopback and replaces with
     * a public resolver, so the name resolves to {@code 127.0.0.1}. An IP address is reachable either
     * way, and that is what {@code baseUrl(false)} returns whenever Floci runs in a container. No endpoint is injected
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
        if (addons.isInstalled(cluster, POD_IDENTITY_ADDON)) {
            return true;
        }
        LOG.debugv("EKS pod-identity-webhook: cluster {0} has no {1} addon, admitting pods unchanged",
                cluster.getName(), POD_IDENTITY_ADDON);
        return false;
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
        return patch(pod, tokenVolume(), tokenMount(), Set.of(), container -> {
            List<Object> env = asList(container.get("env"));
            List<Map<String, Object>> missing = new ArrayList<>();
            for (Map<String, Object> variable : credentialsEnv()) {
                if (env == null || !containsNamed(env, String.valueOf(variable.get("name")))) {
                    missing.add(variable);
                }
            }
            missing.addAll(missingEndpointEnv(container, endpointUrl));
            return missing;
        });
    }

    /**
     * The JSONPatch of IAM roles for service accounts, as the upstream webhook makes it: a projected token for STS,
     * mounted read-only in every init container and container not named in {@value #ANNOTATION_SKIP_CONTAINERS},
     * with {@code AWS_ROLE_ARN} and {@code AWS_WEB_IDENTITY_TOKEN_FILE}, {@code AWS_STS_REGIONAL_ENDPOINTS} while
     * regional endpoints are on, and {@code AWS_REGION} and {@code AWS_DEFAULT_REGION} set to the cluster's Region,
     * which the SDK needs for its STS call. A container setting either variable of a pair gets neither, as upstream,
     * and Floci's endpoint variables follow {@link #buildPatch}'s rules.
     */
    static List<Map<String, Object>> buildWebIdentityPatch(Map<String, Object> pod, WebIdentity identity,
                                                           String region, String endpointUrl) {
        boolean tokenVolumeUsable = tokenVolumeUsable(pod);
        return patch(pod, webIdentityVolume(identity), webIdentityMount(), skippedContainers(pod), container -> {
            List<Object> env = asList(container.get("env"));
            List<Map<String, Object>> missing = new ArrayList<>();
            if (tokenVolumeUsable && tokenMountUsable(container)
                    && !sets(env, ENV_ROLE_ARN) && !sets(env, ENV_WEB_IDENTITY_TOKEN_FILE)) {
                missing.add(Map.of("name", ENV_ROLE_ARN, "value", identity.roleArn()));
                missing.add(Map.of("name", ENV_WEB_IDENTITY_TOKEN_FILE, "value", IRSA_TOKEN_FILE));
            }
            if (identity.regionalSts() && !sets(env, ENV_STS_REGIONAL_ENDPOINTS)) {
                missing.add(Map.of("name", ENV_STS_REGIONAL_ENDPOINTS, "value", "regional"));
            }
            if (region != null && !sets(env, ENV_REGION) && !sets(env, ENV_DEFAULT_REGION)) {
                missing.add(Map.of("name", ENV_DEFAULT_REGION, "value", region));
                missing.add(Map.of("name", ENV_REGION, "value", region));
            }
            missing.addAll(missingEndpointEnv(container, endpointUrl));
            return missing;
        });
    }

    /**
     * The pod volume unless the pod has one of its name, then for every init container and container not in
     * {@code skipped}, the mount unless it has one of its name and the environment variables {@code missingEnv}
     * finds it lacks.
     */
    private static List<Map<String, Object>> patch(Map<String, Object> pod, Map<String, Object> volume,
                                                   Map<String, Object> mount, Set<String> skipped,
                                                   Function<Map<String, Object>, List<Map<String, Object>>> missingEnv) {
        Map<String, Object> spec = asMap(pod.get("spec"));
        if (spec == null) {
            return List.of();
        }
        List<Map<String, Object>> patch = new ArrayList<>();
        List<Object> volumes = asList(spec.get("volumes"));
        String volumeName = String.valueOf(volume.get("name"));
        if (volumes == null) {
            patch.add(operation("/spec/volumes", List.of(volume)));
        } else if (!containsNamed(volumes, volumeName)) {
            patch.add(operation("/spec/volumes/-", volume));
        }
        for (String field : List.of("initContainers", "containers")) {
            List<Object> containers = asList(spec.get(field));
            if (containers == null) {
                continue;
            }
            for (int index = 0; index < containers.size(); index++) {
                Map<String, Object> container = asMap(containers.get(index));
                if (container == null || skipped.contains(String.valueOf(container.get("name")))) {
                    continue;
                }
                String path = "/spec/" + field + "/" + index;
                List<Object> mounts = asList(container.get("volumeMounts"));
                if (mounts == null) {
                    patch.add(operation(path + "/volumeMounts", List.of(mount)));
                } else if (!containsNamed(mounts, volumeName)) {
                    patch.add(operation(path + "/volumeMounts/-", mount));
                }
                List<Map<String, Object>> missing = missingEnv.apply(container);
                if (missing.isEmpty()) {
                    continue;
                }
                if (container.get("env") == null) {
                    patch.add(operation(path + "/env", missing));
                } else {
                    for (Map<String, Object> variable : missing) {
                        patch.add(operation(path + "/env/-", variable));
                    }
                }
            }
        }
        return patch;
    }

    /**
     * Whether the pod's {@value #IRSA_VOLUME_NAME} volume, which the patch keeps when the pod has one, projects a
     * service account token at {@value #IRSA_TOKEN_FILE_NAME}; true when it has none, as the patch adds it. A volume
     * of that name holding anything else would leave {@code AWS_WEB_IDENTITY_TOKEN_FILE} naming no token, so the
     * role pair is not injected then.
     */
    private static boolean tokenVolumeUsable(Map<String, Object> pod) {
        Map<String, Object> spec = asMap(pod.get("spec"));
        List<Object> volumes = spec == null ? null : asList(spec.get("volumes"));
        Map<String, Object> volume = volumes == null ? null : named(volumes, IRSA_VOLUME_NAME);
        if (volume == null) {
            return true;
        }
        Map<String, Object> projected = asMap(volume.get("projected"));
        List<Object> sources = projected == null ? null : asList(projected.get("sources"));
        if (sources == null) {
            return false;
        }
        for (Object source : sources) {
            Map<String, Object> entry = asMap(source);
            Map<String, Object> token = entry == null ? null : asMap(entry.get("serviceAccountToken"));
            if (token != null && IRSA_TOKEN_FILE_NAME.equals(token.get("path"))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the container's {@value #IRSA_VOLUME_NAME} mount, kept when it has one, is at the token's path. */
    private static boolean tokenMountUsable(Map<String, Object> container) {
        List<Object> mounts = asList(container.get("volumeMounts"));
        Map<String, Object> mount = mounts == null ? null : named(mounts, IRSA_VOLUME_NAME);
        return mount == null || IRSA_MOUNT_PATH.equals(mount.get("mountPath"));
    }

    /** The endpoint variables the container lacks, under the rules {@link #buildPatch} describes. */
    private static List<Map<String, Object>> missingEndpointEnv(Map<String, Object> container, String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return List.of();
        }
        List<Object> env = asList(container.get("env"));
        List<Object> envFrom = asList(container.get("envFrom"));
        if (envFrom != null && !envFrom.isEmpty()) {
            return List.of();
        }
        if (sets(env, ENV_ENDPOINT_URL)) {
            return List.of();
        }
        List<Map<String, Object>> missing = new ArrayList<>();
        missing.add(Map.of("name", ENV_ENDPOINT_URL, "value", endpointUrl));
        if (!sets(env, ENV_ENDPOINT_URL_STS)) {
            missing.add(Map.of("name", ENV_ENDPOINT_URL_STS, "value", endpointUrl));
        }
        return missing;
    }

    private static boolean sets(List<Object> env, String name) {
        return env != null && containsNamed(env, name);
    }

    /** The names {@value #ANNOTATION_SKIP_CONTAINERS} lists, comma-separated, in the pod's annotations. */
    private static Set<String> skippedContainers(Map<String, Object> pod) {
        String names = annotationsOf(pod).get(ANNOTATION_SKIP_CONTAINERS);
        Set<String> skipped = new HashSet<>();
        if (names != null) {
            for (String name : names.split(",")) {
                if (!name.isBlank()) {
                    skipped.add(name.trim());
                }
            }
        }
        return skipped;
    }

    static Map<String, Object> webIdentityVolume(WebIdentity identity) {
        return Map.of(
                "name", IRSA_VOLUME_NAME,
                "projected", Map.of("sources", List.of(Map.of("serviceAccountToken", Map.of(
                        "audience", identity.audience(),
                        "expirationSeconds", identity.tokenExpiration(),
                        "path", IRSA_TOKEN_FILE_NAME)))));
    }

    static Map<String, Object> webIdentityMount() {
        return Map.of("name", IRSA_VOLUME_NAME, "mountPath", IRSA_MOUNT_PATH, "readOnly", true);
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
        return named(entries, name) != null;
    }

    private static Map<String, Object> named(List<Object> entries, String name) {
        for (Object entry : entries) {
            Map<String, Object> map = asMap(entry);
            if (map != null && name.equals(map.get("name"))) {
                return map;
            }
        }
        return null;
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
