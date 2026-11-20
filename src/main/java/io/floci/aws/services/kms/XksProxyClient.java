package io.floci.aws.services.kms;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.ContainerCaBundle;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.SsrfProtection;
import io.floci.aws.core.common.ValidatingDnsResolver;
import io.floci.aws.core.common.auth.SigV4Canonicalization;
import io.floci.aws.core.common.auth.SigV4RequestValidator;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** AWS KMS XKS v1 health-check client for PUBLIC_ENDPOINT custom key stores. */
@ApplicationScoped
public class XksProxyClient implements AutoCloseable {

    private static final Duration HTTP_TIMEOUT = Duration.ofMillis(250);
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private static final String SERVICE = "kms-xks-proxy";
    private static final Pattern REGION = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern URI_PATH = Pattern.compile("/[A-Za-z0-9/_-]{9,127}");
    private static final Pattern ENDPOINT = Pattern.compile("https://[A-Za-z0-9.-]+");
    private static final Pattern DNS_HOSTNAME = Pattern.compile(
            "(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;
    private final boolean allowTestEndpoint;
    private final ThreadPoolExecutor requestExecutor;

    @Inject
    public XksProxyClient(ObjectMapper objectMapper, EmulatorConfig config) {
        this(objectMapper, SystemDefaultDnsResolver.INSTANCE,
                ContainerCaBundle.sslContext(config).orElse(null), false,
                parseAllowedPrivateHosts(config.services().kms().xksAllowedPrivateProxyHosts().orElse(List.of())));
    }

    XksProxyClient(ObjectMapper objectMapper, DnsResolver dnsResolver, SSLContext sslContext) {
        this(objectMapper, dnsResolver, sslContext, false);
    }

    /** Test-only seam for an in-suite HTTPS fake bound to loopback and an ephemeral port. */
    XksProxyClient(ObjectMapper objectMapper, DnsResolver dnsResolver, SSLContext sslContext,
                   boolean allowTestEndpoint) {
        this(objectMapper, dnsResolver, sslContext, allowTestEndpoint, Set.of());
    }

    XksProxyClient(ObjectMapper objectMapper, DnsResolver dnsResolver, SSLContext sslContext,
                   boolean allowTestEndpoint, Set<String> allowedPrivateHosts) {
        this.objectMapper = objectMapper;
        this.allowTestEndpoint = allowTestEndpoint;
        ThreadFactory requestThreadFactory = task -> {
            Thread thread = new Thread(task, "xks-proxy-health");
            thread.setDaemon(true);
            return thread;
        };
        this.requestExecutor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), requestThreadFactory, new ThreadPoolExecutor.AbortPolicy());
        DnsResolver publicOnlyResolver = allowTestEndpoint ? dnsResolver
                : validatingResolver(dnsResolver, allowedPrivateHosts);
        Timeout timeout = Timeout.of(allowTestEndpoint ? Duration.ofSeconds(3) : HTTP_TIMEOUT);
        PoolingHttpClientConnectionManagerBuilder connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(publicOnlyResolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout)
                        .setSocketTimeout(timeout).build())
                .setMaxConnTotal(8).setMaxConnPerRoute(2);
        if (sslContext != null) {
            connections.setTlsSocketStrategy(ClientTlsStrategyBuilder.create().setSslContext(sslContext).buildClassic());
        }
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connections.build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(timeout)
                        .setResponseTimeout(timeout)
                        .setRedirectsEnabled(false).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableAuthCaching().disableContentCompression().build();
    }

    /**
     * Calls the standard XKS {@code GetHealthStatus} operation. The secret is used only to sign
     * this request; it is never included in an exception message or log entry.
     */
    public void requireHealthy(URI endpoint, String uriPath, String accessKeyId, String secretAccessKey,
                               String region, String kmsOperation) {
        requireHealthy(endpoint, uriPath, accessKeyId, secretAccessKey, region, kmsOperation, UUID.randomUUID().toString());
    }

    public void requireHealthy(URI endpoint, String uriPath, String accessKeyId, String secretAccessKey,
                               String region, String kmsOperation, String kmsRequestId) {
        URI target = healthUri(endpoint, uriPath, allowTestEndpoint);
        if (accessKeyId == null || !XksCustomKeyStoreValidation.ACCESS_KEY_ID.matcher(accessKeyId).matches()
                || secretAccessKey == null || !XksCustomKeyStoreValidation.SECRET_ACCESS_KEY.matcher(secretAccessKey).matches()
                || region == null || !REGION.matcher(region).matches()
                || isBlank(kmsOperation) || !kmsOperation.matches("[A-Za-z0-9]{1,64}")) {
            throw new XksProxyException("XksProxyInvalidConfigurationException",
                    "XKS proxy credentials, Region, or KMS operation are invalid", 400);
        }

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of("requestMetadata", Map.of(
                    "kmsRequestId", kmsRequestId, "kmsOperation", kmsOperation)));
            String timestamp = AMZ_DATE.format(Instant.now());
            String date = timestamp.substring(0, 8);
            String host = target.getRawAuthority().toLowerCase(Locale.ROOT);
            String signedHeaders = "host;x-amz-date";
            Map<String, String> headers = new TreeMap<>();
            headers.put("host", host);
            headers.put("x-amz-date", timestamp);
            String canonicalHeaders = SigV4Canonicalization.canonicalHeaders(signedHeaders, headers::get);
            String canonicalRequest = "POST\n" + target.getRawPath() + "\n\n" + canonicalHeaders + "\n"
                    + signedHeaders + "\n" + Digests.sha256Hex(body);
            String scope = date + "/" + region + "/" + SERVICE + "/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n"
                    + Digests.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            byte[] signingKey = SigV4RequestValidator.deriveSigningKey(secretAccessKey, date, region, SERVICE);
            String signature;
            byte[] signatureBytes = null;
            try {
                signatureBytes = SigV4RequestValidator.hmacSha256(signingKey, stringToSign);
                signature = SigV4RequestValidator.hexEncode(signatureBytes);
            } finally {
                Arrays.fill(signingKey, (byte) 0);
                if (signatureBytes != null) {
                    Arrays.fill(signatureBytes, (byte) 0);
                }
            }
            String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

            HttpPost request = new HttpPost(target);
            request.setHeader("Host", host);
            request.setHeader("Content-Type", "application/json");
            request.setHeader("X-Amz-Date", timestamp);
            request.setHeader("Authorization", authorization);
            request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));

            executeWithDeadline(request, () -> httpClient.execute(request, response -> {
                int status = response.getCode();
                byte[] responseBody;
                if (response.getEntity() == null) {
                    responseBody = new byte[0];
                } else {
                    try (InputStream stream = response.getEntity().getContent()) {
                        responseBody = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                    }
                }
                if (responseBody.length > MAX_RESPONSE_BYTES) {
                    throw new XksProxyException("XksProxyInvalidResponseException",
                            "XKS proxy health response exceeds the permitted size", 400);
                }
                if (status == 408 || status == 504) {
                    throw new XksProxyException("XksProxyUriUnreachableException",
                            "XKS proxy health endpoint timed out", 400, null, "XKS_PROXY_TIMED_OUT");
                }
                if (status == 404) {
                    throw new XksProxyException("XksProxyInvalidConfigurationException",
                            "XKS proxy health endpoint path was not found", 400);
                }
                if (status == 401 || status == 403) {
                    throw new XksProxyException("XksProxyIncorrectAuthenticationCredentialException",
                            "XKS proxy rejected the configured authentication credentials", 400);
                }
                if (status != 200) {
                    throw new XksProxyException("XksProxyUriUnreachableException",
                            "XKS proxy health endpoint returned HTTP " + status, 400);
                }
                JsonNode responseJson;
                try {
                    responseJson = objectMapper.reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(responseBody);
                } catch (IOException e) {
                    throw new XksProxyException("XksProxyInvalidResponseException",
                            "XKS proxy returned malformed health response JSON", 400, e);
                }
                if (responseJson == null || !responseJson.isObject()
                        || !responseJson.path("xksProxyFleetSize").isIntegralNumber()
                        || !responseJson.path("xksProxyFleetSize").canConvertToInt()
                        || responseJson.path("xksProxyFleetSize").asInt() < 1
                        || isBlank(responseJson.path("xksProxyVendor").asText(null))
                        || isBlank(responseJson.path("xksProxyModel").asText(null))
                        || isBlank(responseJson.path("ekmVendor").asText(null))
                        || !responseJson.path("ekmFleetDetails").isArray()
                        || responseJson.path("ekmFleetDetails").isEmpty()
                        || responseJson.path("ekmFleetDetails").size() > 10) {
                    throw new XksProxyException("XksProxyInvalidResponseException",
                            "XKS proxy returned an invalid health response", 400);
                }
                boolean canServeTraffic = false;
                for (JsonNode manager : responseJson.path("ekmFleetDetails")) {
                    String managerStatus = manager.path("healthStatus").asText(null);
                    if (!manager.isObject() || isBlank(manager.path("id").asText(null))
                            || isBlank(manager.path("model").asText(null))
                            || !("ACTIVE".equals(managerStatus) || "DEGRADED".equals(managerStatus)
                                || "UNAVAILABLE".equals(managerStatus))) {
                        throw new XksProxyException("XksProxyInvalidResponseException",
                                "XKS proxy returned an invalid health response", 400);
                    }
                    if (!"UNAVAILABLE".equals(managerStatus)) {
                        canServeTraffic = true;
                    }
                }
                if (canServeTraffic) {
                    return null;
                }
                throw new XksProxyException("XksProxyUriUnreachableException",
                        "XKS proxy reports that all external key managers are unavailable", 400);
            }));
        } catch (XksProxyException e) {
            throw e;
        } catch (IOException e) {
            boolean timeout = isTimeout(e);
            boolean tlsFailure = isTlsFailure(e);
            String connectionCode = timeout ? "XKS_PROXY_TIMED_OUT"
                    : tlsFailure ? "XKS_PROXY_INVALID_TLS_CONFIGURATION" : "XKS_PROXY_NOT_REACHABLE";
            throw new XksProxyException("XksProxyUriUnreachableException",
                    timeout ? "The XKS proxy health endpoint did not respond within 250 milliseconds"
                            : tlsFailure ? "The XKS proxy TLS connection could not be established"
                            : "Could not reach the configured XKS proxy health endpoint", 400, e, connectionCode);
        } catch (Exception e) {
            throw new XksProxyException("XksProxyInvalidResponseException",
                    "Could not process the XKS proxy health response", 400, e);
        }
    }

    private <T> T executeWithDeadline(HttpPost request, Callable<T> operation) throws IOException {
        Future<T> future;
        try {
            future = requestExecutor.submit(operation);
        } catch (RejectedExecutionException e) {
            throw new IOException("XKS proxy request capacity is exhausted", e);
        }
        try {
            return future.get(HTTP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            request.cancel();
            future.cancel(true);
            SocketTimeoutException timeout = new SocketTimeoutException("XKS proxy request exceeded the 250 ms deadline");
            timeout.initCause(e);
            throw timeout;
        } catch (InterruptedException e) {
            request.cancel();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for XKS proxy", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("XKS proxy request failed", cause);
        }
    }

    static URI healthUri(URI endpoint, String uriPath) {
        return healthUri(endpoint, uriPath, false);
    }

    private static URI healthUri(URI endpoint, String uriPath, boolean allowTestEndpoint) {
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme()) || isBlank(endpoint.getHost())
                || (!allowTestEndpoint && (!ENDPOINT.matcher(endpoint.toString()).matches()
                    || endpoint.getPort() != -1 || endpoint.getHost().matches("[0-9.]+")))
                || endpoint.getRawUserInfo() != null
                || (endpoint.getRawPath() != null && !endpoint.getRawPath().isEmpty())
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new XksProxyException("XksProxyInvalidConfigurationException",
                    "XKS proxy endpoint must be an HTTPS origin without a port, path, query, or user information", 400);
        }
        if (isBlank(uriPath) || uriPath.length() < 10 || uriPath.length() > 128 || !URI_PATH.matcher(uriPath).matches()
                || !uriPath.endsWith("/kms/xks/v1")
                || uriPath.contains("..") || uriPath.contains("//") || uriPath.endsWith("/")) {
            throw new XksProxyException("XksProxyInvalidConfigurationException",
                    "XKS proxy URI path is invalid", 400);
        }
        try {
            URI target = URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority() + uriPath + "/health");
            if (target.toString().length() > 256) {
                throw new XksProxyException("XksProxyInvalidConfigurationException",
                        "XKS proxy URI must not exceed 256 characters", 400);
            }
            return target;
        } catch (IllegalArgumentException e) {
            throw new XksProxyException("XksProxyInvalidConfigurationException", "XKS proxy URI is invalid", 400, e);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (IOException ignored) {
            // The client is already shutting down; no credentials or request data are logged.
        } finally {
            requestExecutor.shutdownNow();
        }
    }

    private static boolean isTlsFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String name = cause.getClass().getSimpleName();
            if (name.contains("Timeout") || name.contains("TimedOut")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static Set<String> parseAllowedPrivateHosts(List<String> configuredHosts) {
        if (configuredHosts == null || configuredHosts.isEmpty()) {
            return Set.of();
        }
        Set<String> hosts = new HashSet<>();
        for (String configuredHost : configuredHosts) {
            if (configuredHost == null || configuredHost.isBlank()) {
                continue;
            }
            String host = normalizeHost(configuredHost);
            if (!DNS_HOSTNAME.matcher(host).matches() || host.matches("[0-9.]+")) {
                throw new IllegalArgumentException("XKS allowed private proxy hosts must be exact DNS hostnames");
            }
            hosts.add(host);
        }
        return Set.copyOf(hosts);
    }

    static DnsResolver validatingResolver(DnsResolver delegate, Set<String> allowedPrivateHosts) {
        Set<String> allowedHosts = allowedPrivateHosts.stream()
                .map(XksProxyClient::normalizeHost)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ValidatingDnsResolver(delegate, (host, addresses) -> {
            String normalizedHost = normalizeHost(host);
            boolean explicitlyTrustedPrivateHost = allowedHosts.contains(normalizedHost);
            for (InetAddress address : addresses) {
                boolean blocked = explicitlyTrustedPrivateHost
                        ? !isExplicitlyAllowedPrivateAddress(address)
                        : SsrfProtection.isBlockedAddress(address);
                if (blocked) {
                    throw new UnknownHostException(explicitlyTrustedPrivateHost
                            ? "XKS proxy host resolves outside explicitly trusted private ranges"
                            : "XKS proxy host resolves to a non-public address");
                }
            }
        }, XksProxyClient::normalizeHost);
    }

    private static String normalizeHost(String host) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static boolean isExplicitlyAllowedPrivateAddress(InetAddress address) {
        if (SsrfProtection.isMetadataAddress(address) || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address.isLoopbackAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 10 || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        }
        return bytes.length == 16 && (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc;
    }

    public static final class XksProxyException extends AwsException {
        private final String connectionErrorCode;

        public XksProxyException(String errorCode, String message, int httpStatus) {
            this(errorCode, message, httpStatus, null, null);
        }

        public XksProxyException(String errorCode, String message, int httpStatus, Throwable cause) {
            this(errorCode, message, httpStatus, cause, null);
        }

        public XksProxyException(String errorCode, String message, int httpStatus, Throwable cause,
                                 String connectionErrorCode) {
            super(errorCode, message, httpStatus);
            this.connectionErrorCode = connectionErrorCode;
            if (cause != null) {
                initCause(cause);
            }
        }

        public String connectionErrorCode() {
            if (connectionErrorCode != null) {
                return connectionErrorCode;
            }
            return connectionErrorCodeFor(getErrorCode());
        }

        public static String connectionErrorCodeFor(String errorCode) {
            return switch (errorCode == null ? "" : errorCode) {
                case "XksProxyIncorrectAuthenticationCredentialException" -> "XKS_PROXY_ACCESS_DENIED";
                case "XksProxyInvalidResponseException" -> "XKS_PROXY_INVALID_RESPONSE";
                case "XksProxyInvalidConfigurationException" -> "XKS_PROXY_INVALID_CONFIGURATION";
                case "KMSInternalException", "InternalFailure", "ConfigurationError" -> "INTERNAL_ERROR";
                default -> "XKS_PROXY_NOT_REACHABLE";
            };
        }
    }
}
