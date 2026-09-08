package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Thin REST client for the subset of the Kubernetes API the Lambda kubernetes executor needs:
 * create/get/list/delete pods, create-or-update a ConfigMap, and stream a pod's logs. Uses the
 * JDK {@link HttpClient} (as the rest of Floci does for outbound calls, e.g.
 * {@code FlinkRestClient}) instead of a Kubernetes SDK, so the executor adds no dependency
 * beyond what Floci already ships: see issue #2914, where the fabric8 client (unconditionally
 * on the classpath through {@code quarkus-kubernetes-client}) was reachable in every native
 * image regardless of whether the kubernetes executor was ever used.
 *
 * <p>Connection resolution mirrors kubectl's, minus exec/auth-provider credential plugins:
 * <ul>
 *   <li>In-cluster: the mounted service account token and CA cert. The token is re-read on
 *       every request since projected service account tokens rotate.</li>
 *   <li>Otherwise, a kubeconfig ({@code $KUBECONFIG}, first entry if multiple; else
 *       {@code ~/.kube/config}), current-context only, with a static bearer token or a
 *       client-certificate/client-key credential (PKCS#8 only). {@code insecure-skip-tls-verify}
 *       is honored (skips both chain and hostname validation, matching kubectl), which covers
 *       most kind/minikube configs. The {@code aws eks get-token --cluster-name <name>} exec
 *       plugin is also recognized and its token minted natively, see {@link EksTokenMinter};
 *       any other exec command, {@code --role-arn}, or an auth-provider plugin (e.g. gcloud)
 *       is not supported and fails with a clear error.</li>
 * </ul>
 */
@ApplicationScoped
public class KubernetesApiClient {

    private static final Path SERVICE_ACCOUNT_DIR = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
    private static final Path SERVICE_ACCOUNT_TOKEN = SERVICE_ACCOUNT_DIR.resolve("token");
    private static final Path SERVICE_ACCOUNT_CA = SERVICE_ACCOUNT_DIR.resolve("ca.crt");
    private static final long POLL_INTERVAL_MS = 500;

    /** Backs kubeconfig's {@code insecure-skip-tls-verify: true} (kind/minikube dev configs). */
    private static final X509TrustManager INSECURE_TRUST_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();
    private volatile boolean initialized;
    private URI baseUri;
    private HttpClient http;
    private Supplier<String> tokenSupplier;

    public KubernetesApiClient() {
    }

    /** Test-only: points the client at an explicit, already-configured endpoint. */
    KubernetesApiClient(URI baseUri, HttpClient http, Supplier<String> tokenSupplier) {
        this.baseUri = baseUri;
        this.http = http;
        this.tokenSupplier = tokenSupplier;
        this.initialized = true;
    }

    /** Resolves the connection (in-cluster or kubeconfig) now, so bad config fails fast. */
    public void initialize() {
        ensureInitialized();
    }

    public JsonNode createPod(String namespace, JsonNode pod) {
        return send("POST", podsPath(namespace), pod, 201);
    }

    public Optional<JsonNode> getPod(String namespace, String name) {
        return getOptional(podPath(namespace, name));
    }

    public List<JsonNode> listPods(String namespace, Map<String, String> labelSelector) {
        var selector = labelSelector.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        var path = podsPath(namespace) + "?labelSelector=" + URLEncoder.encode(selector, StandardCharsets.UTF_8);
        var items = new ArrayList<JsonNode>();
        send("GET", path, null, 200).path("items").forEach(items::add);
        return items;
    }

    /** No-op if the pod is already gone. */
    public void deletePod(String namespace, String name) {
        try {
            send("DELETE", podPath(namespace, name) + "?gracePeriodSeconds=0", null, 200);
        } catch (KubernetesApiException e) {
            if (e.getStatusCode() != 404) {
                throw e;
            }
        }
    }

    /**
     * Polls the pod every {@value #POLL_INTERVAL_MS}ms (a plain {@code GET}, not a watch) until
     * {@code condition} is true, passing {@code null} once the pod no longer exists. Returns the
     * value that satisfied the condition.
     *
     * @throws IllegalStateException if {@code condition} is not satisfied within {@code timeoutSeconds}
     */
    public JsonNode waitForPod(String namespace, String name, Predicate<JsonNode> condition, int timeoutSeconds) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            var pod = getPod(namespace, name).orElse(null);
            if (condition.test(pod)) {
                return pod;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out after " + timeoutSeconds + "s waiting for pod " + name);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for pod " + name, e);
            }
        }
    }

    /**
     * Creates the ConfigMap, or replaces its data in place if one by that name already exists.
     * Never deletes, matching what {@code ensureCaConfigMap} needs: publish-once, refresh-on-restart.
     */
    public void createOrUpdateConfigMap(String namespace, ObjectNode configMap) {
        try {
            send("POST", configMapsPath(namespace), configMap, 201);
        } catch (KubernetesApiException e) {
            if (e.getStatusCode() != 409) {
                throw e;
            }
            var name = configMap.path("metadata").path("name").asText();
            var existing = getOptional(configMapPath(namespace, name)).orElseThrow(() -> e);
            ((ObjectNode) configMap.path("metadata"))
                    .put("resourceVersion", existing.path("metadata").path("resourceVersion").asText());
            send("PUT", configMapPath(namespace, name), configMap, 200);
        }
    }

    /**
     * Opens a following log stream ({@code GET .../log?follow=true}), same shape as
     * {@code kubectl logs -f}. The caller reads and closes the stream; closing it ends the
     * underlying connection and the kubelet stops the follow.
     */
    public InputStream openPodLogStream(String namespace, String podName, String containerName) {
        ensureInitialized();
        var path = podPath(namespace, podName) + "/log?container="
                + URLEncoder.encode(containerName, StandardCharsets.UTF_8) + "&follow=true";
        try {
            var response = http.send(requestBuilder(path).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                var body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new KubernetesApiException("GET", path, response.statusCode(), body);
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open log stream for pod " + podName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted opening log stream for pod " + podName, e);
        }
    }

    private JsonNode send(String method, String path, JsonNode body, int expectedStatus) {
        ensureInitialized();
        try {
            var builder = requestBuilder(path);
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != expectedStatus) {
                throw new KubernetesApiException(method, path, response.statusCode(),
                        new String(response.body(), StandardCharsets.UTF_8));
            }
            return response.body().length == 0 ? NullNode.getInstance() : mapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Kubernetes API " + method + " " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Kubernetes API " + method + " " + path, e);
        }
    }

    private Optional<JsonNode> getOptional(String path) {
        ensureInitialized();
        try {
            var response = http.send(requestBuilder(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new KubernetesApiException("GET", path, response.statusCode(),
                        new String(response.body(), StandardCharsets.UTF_8));
            }
            return Optional.of(mapper.readTree(response.body()));
        } catch (IOException e) {
            throw new UncheckedIOException("Kubernetes API GET " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Kubernetes API GET " + path, e);
        }
    }

    private HttpRequest.Builder requestBuilder(String path) {
        var builder = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(30));
        if (tokenSupplier != null) {
            builder.header("Authorization", "Bearer " + tokenSupplier.get());
        }
        return builder;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (lock) {
            if (initialized) {
                return;
            }
            if (isRunningInCluster()) {
                resolveInCluster();
            } else {
                resolveFromKubeconfig(kubeconfigPath());
            }
            initialized = true;
        }
    }

    private static boolean isRunningInCluster() {
        return System.getenv("KUBERNETES_SERVICE_HOST") != null || Files.exists(SERVICE_ACCOUNT_TOKEN);
    }

    /**
     * Test-only: resolves from an explicit kubeconfig and marks the client ready, bypassing the
     * in-cluster/{@code $KUBECONFIG} auto-detection {@link #ensureInitialized()} would otherwise do.
     */
    void initializeFromKubeconfigForTest(Path path) {
        resolveFromKubeconfig(path);
        this.initialized = true;
    }

    private void resolveInCluster() {
        var host = System.getenv("KUBERNETES_SERVICE_HOST");
        var port = System.getenv("KUBERNETES_SERVICE_PORT");
        var authority = (host != null && port != null) ? host + ":" + port : "kubernetes.default.svc";
        this.baseUri = URI.create("https://" + authority);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(sslContext(trustManagerFromCaFile(SERVICE_ACCOUNT_CA), null))
                .build();
        this.tokenSupplier = () -> {
            try {
                return Files.readString(SERVICE_ACCOUNT_TOKEN, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Could not read in-cluster service account token at " + SERVICE_ACCOUNT_TOKEN
                                + ": " + e.getMessage(), e);
            }
        };
    }

    private void resolveFromKubeconfig(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    "The kubernetes Lambda executor needs either an in-cluster service account or a "
                            + "kubeconfig, and found neither: no service account at " + SERVICE_ACCOUNT_DIR
                            + " and no kubeconfig at " + path + ". Set KUBECONFIG or place one at ~/.kube/config.");
        }
        JsonNode root;
        try (var in = Files.newInputStream(path)) {
            root = new ObjectMapper(new YAMLFactory()).readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read kubeconfig at " + path + ": " + e.getMessage(), e);
        }

        var currentContextName = root.path("current-context").asText(null);
        if (currentContextName == null || currentContextName.isBlank()) {
            throw new IllegalStateException("kubeconfig at " + path + " has no current-context set");
        }
        var context = findNamed(root.path("contexts"), currentContextName, "context")
                .orElseThrow(() -> new IllegalStateException(
                        "kubeconfig context '" + currentContextName + "' not found in " + path));
        var clusterName = context.path("cluster").asText(null);
        var cluster = findNamed(root.path("clusters"), clusterName, "cluster")
                .orElseThrow(() -> new IllegalStateException(
                        "kubeconfig cluster '" + clusterName + "' not found in " + path));
        var userName = context.path("user").asText(null);
        var user = userName == null ? JsonNodeFactory.instance.objectNode()
                : findNamed(root.path("users"), userName, "user")
                        .orElseGet(JsonNodeFactory.instance::objectNode);

        var server = cluster.path("server").asText(null);
        if (server == null || server.isBlank()) {
            throw new IllegalStateException("kubeconfig cluster '" + clusterName + "' has no server URL in " + path);
        }
        this.baseUri = URI.create(server);

        // Matches kubectl: when set, this wins over any certificate-authority (chain
        // validation is skipped either way, so a CA to validate against is moot).
        var insecureSkipTlsVerify = cluster.path("insecure-skip-tls-verify").asBoolean(false);
        X509TrustManager trustManager;
        if (insecureSkipTlsVerify) {
            trustManager = INSECURE_TRUST_MANAGER;
        } else {
            var caBytes = pemBytes(cluster, "certificate-authority", "certificate-authority-data", path);
            trustManager = caBytes != null ? trustManagerFromPem(caBytes) : defaultTrustManager();
        }

        var certBytes = pemBytes(user, "client-certificate", "client-certificate-data", path);
        var keyBytes = pemBytes(user, "client-key", "client-key-data", path);
        KeyManager[] keyManagers = null;
        if (certBytes != null && keyBytes != null) {
            keyManagers = keyManagersFromPem(certBytes, keyBytes);
        }

        var token = user.path("token").asText(null);
        Supplier<String> execTokenSupplier = user.has("exec")
                ? EksTokenMinter.tokenSupplierIfRecognized(user.path("exec")).orElse(null)
                : null;
        if (token == null && keyManagers == null && execTokenSupplier == null) {
            if (user.has("exec") || user.has("auth-provider")) {
                throw new IllegalStateException(
                        "kubeconfig user '" + userName + "' uses an exec or auth-provider credential "
                                + "plugin the kubernetes Lambda executor does not recognize (only 'aws eks "
                                + "get-token --cluster-name <name> [--region <region>]' is supported). Use "
                                + "a static token, client-certificate/client-key credential, or that exec "
                                + "shape instead.");
            }
            throw new IllegalStateException("kubeconfig user '" + userName
                    + "' has no supported credential (token or client-certificate/client-key) in " + path);
        }

        var httpBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(sslContext(trustManager, keyManagers));
        if (insecureSkipTlsVerify) {
            // The trust manager above already skips chain validation; this additionally
            // skips hostname verification, matching kubectl's --insecure-skip-tls-verify
            // (otherwise a kind/minikube server cert with no matching SAN still fails).
            var sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("");
            httpBuilder.sslParameters(sslParameters);
        }
        this.http = httpBuilder.build();
        this.tokenSupplier = execTokenSupplier != null ? execTokenSupplier
                : token == null ? null : () -> token;
    }

    private static Path kubeconfigPath() {
        var envValue = System.getenv("KUBECONFIG");
        if (envValue != null && !envValue.isBlank()) {
            var separator = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ";" : ":";
            return Path.of(envValue.split(Pattern.quote(separator))[0]);
        }
        return Path.of(System.getProperty("user.home"), ".kube", "config");
    }

    private static Optional<JsonNode> findNamed(JsonNode array, String name, String childKey) {
        if (name == null) {
            return Optional.empty();
        }
        for (var entry : array) {
            if (name.equals(entry.path("name").asText(null))) {
                return Optional.of(entry.path(childKey));
            }
        }
        return Optional.empty();
    }

    /** Reads a `<field>` (a path, resolved relative to the kubeconfig) or `<field>-data` (base64) entry. */
    private static byte[] pemBytes(JsonNode node, String fileField, String dataField, Path kubeconfigPath) {
        var data = node.path(dataField).asText(null);
        if (data != null && !data.isBlank()) {
            return Base64.getDecoder().decode(data);
        }
        var file = node.path(fileField).asText(null);
        if (file == null || file.isBlank()) {
            return null;
        }
        var resolved = Path.of(file).isAbsolute() ? Path.of(file) : kubeconfigPath.getParent().resolve(file);
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read kubeconfig-referenced file " + resolved + ": " + e.getMessage(), e);
        }
    }

    private static X509TrustManager trustManagerFromCaFile(Path caFile) {
        try {
            return trustManagerFromPem(Files.readAllBytes(caFile));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read in-cluster CA certificate at " + caFile + ": " + e.getMessage(), e);
        }
    }

    private static X509TrustManager trustManagerFromPem(byte[] pem) {
        try {
            var certs = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem));
            var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            var i = 0;
            for (var cert : certs) {
                keyStore.setCertificateEntry("ca" + (i++), cert);
            }
            var trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            return firstX509(trustManagerFactory.getTrustManagers(),
                    "No X509TrustManager produced from the supplied CA certificate(s)");
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(
                    "Could not build a trust manager from the supplied CA certificate: " + e.getMessage(), e);
        }
    }

    private static X509TrustManager defaultTrustManager() {
        try {
            var trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
            return firstX509(trustManagerFactory.getTrustManagers(), "No default X509TrustManager available");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not load the default trust manager: " + e.getMessage(), e);
        }
    }

    private static X509TrustManager firstX509(TrustManager[] managers, String errorIfNone) {
        for (var manager : managers) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException(errorIfNone);
    }

    private static KeyManager[] keyManagersFromPem(byte[] certPem, byte[] keyPem) {
        try {
            var cert = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certPem));
            var privateKey = privateKeyFromPem(keyPem);
            var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setKeyEntry("client", privateKey, new char[0], new Certificate[]{cert});
            var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, new char[0]);
            return keyManagerFactory.getKeyManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(
                    "Could not build a key manager from the kubeconfig client certificate/key: " + e.getMessage(), e);
        }
    }

    private static PrivateKey privateKeyFromPem(byte[] pem) {
        var text = new String(pem, StandardCharsets.US_ASCII);
        if (text.contains("BEGIN RSA PRIVATE KEY") || text.contains("BEGIN EC PRIVATE KEY")) {
            throw new IllegalStateException(
                    "kubeconfig client-key is in PKCS#1 format (BEGIN RSA/EC PRIVATE KEY); only PKCS#8 "
                            + "(BEGIN PRIVATE KEY) is supported. Convert it with: openssl pkcs8 -topk8 "
                            + "-nocrypt -in key.pem -out key-pkcs8.pem");
        }
        var base64 = text.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        var keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64));
        GeneralSecurityException last = null;
        for (var algorithm : new String[]{"RSA", "EC"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
            } catch (GeneralSecurityException e) {
                last = e;
            }
        }
        throw new IllegalStateException("Could not parse kubeconfig client-key as an RSA or EC private key: "
                + (last == null ? "unknown format" : last.getMessage()));
    }

    private static SSLContext sslContext(X509TrustManager trustManager, KeyManager[] keyManagers) {
        try {
            var context = SSLContext.getInstance("TLS");
            context.init(keyManagers, new TrustManager[]{trustManager}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not initialize TLS for the Kubernetes API client: "
                    + e.getMessage(), e);
        }
    }

    private static String podsPath(String namespace) {
        return "/api/v1/namespaces/" + encode(namespace) + "/pods";
    }

    private static String podPath(String namespace, String name) {
        return podsPath(namespace) + "/" + encode(name);
    }

    private static String configMapsPath(String namespace) {
        return "/api/v1/namespaces/" + encode(namespace) + "/configmaps";
    }

    private static String configMapPath(String namespace, String name) {
        return configMapsPath(namespace) + "/" + encode(name);
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
