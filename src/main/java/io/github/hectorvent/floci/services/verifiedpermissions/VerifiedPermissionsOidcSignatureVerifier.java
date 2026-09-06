package io.github.hectorvent.floci.services.verifiedpermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.Timeout;
import org.jboss.logging.Logger;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class VerifiedPermissionsOidcSignatureVerifier implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(VerifiedPermissionsOidcSignatureVerifier.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;
    private final Map<String, CachedJwks> jwksCache = new ConcurrentHashMap<>();

    @Inject
    public VerifiedPermissionsOidcSignatureVerifier(ObjectMapper objectMapper) {
        this(objectMapper, SystemDefaultDnsResolver.INSTANCE);
    }

    VerifiedPermissionsOidcSignatureVerifier(ObjectMapper objectMapper, DnsResolver dnsResolver) {
        this.objectMapper = objectMapper;
        DnsResolver validatingResolver = new PublicOnlyDnsResolver(dnsResolver);
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(HTTP_TIMEOUT))
                .setSocketTimeout(Timeout.of(HTTP_TIMEOUT))
                .build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(validatingResolver)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(HTTP_TIMEOUT))
                .setResponseTimeout(Timeout.of(HTTP_TIMEOUT))
                .setRedirectsEnabled(false)
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build();
    }

    public void verify(String token, String issuer) throws VerificationException {
        validatePublicHttpsUri(issuer, "OIDC issuer");
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3) {
            throw new VerificationException("Token is not a well-formed JWT");
        }
        JsonNode header = decodeJson(parts[0]);
        String alg = header.path("alg").asText("");
        if (!"RS256".equals(alg)) {
            throw new VerificationException("Unsupported token signing algorithm: " + (alg.isEmpty() ? "none" : alg));
        }
        String kid = header.path("kid").asText(null);
        if (kid == null || kid.isBlank()) {
            throw new VerificationException("Token header has no kid");
        }
        RSAPublicKey key = resolveKey(issuer, kid);
        if (key == null) {
            throw new VerificationException("No JWKS key matches kid '" + kid + "' for issuer " + issuer);
        }
        if (!signatureValid(parts[0] + "." + parts[1], parts[2], key)) {
            throw new VerificationException("Token signature is invalid");
        }
    }

    private RSAPublicKey resolveKey(String issuer, String kid) throws VerificationException {
        CachedJwks cached = jwksCache.get(issuer);
        if (cached != null && !cached.expired()) {
            RSAPublicKey key = cached.keysByKid().get(kid);
            if (key != null) {
                return key;
            }
        }
        Map<String, RSAPublicKey> fetched = fetchJwks(issuer);
        jwksCache.put(issuer, new CachedJwks(fetched, Instant.now()));
        return fetched.get(kid);
    }

    private Map<String, RSAPublicKey> fetchJwks(String issuer) throws VerificationException {
        String discoveryUrl = issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";
        JsonNode discovery = getJson(discoveryUrl, "OIDC discovery document");
        String jwksUri = discovery.path("jwks_uri").asText(null);
        if (jwksUri == null || jwksUri.isBlank()) {
            throw new VerificationException("OIDC discovery document has no jwks_uri");
        }
        validatePublicHttpsUri(jwksUri, "JWKS document");
        JsonNode jwks = getJson(jwksUri, "JWKS document");
        JsonNode keys = jwks.path("keys");
        if (!keys.isArray()) {
            throw new VerificationException("JWKS document has no keys array");
        }
        Map<String, RSAPublicKey> result = new ConcurrentHashMap<>();
        for (JsonNode jwk : keys) {
            String kty = jwk.path("kty").asText("");
            String keyId = jwk.path("kid").asText(null);
            if (!"RSA".equals(kty) || keyId == null || keyId.isBlank()) {
                continue;
            }
            String modulus = jwk.path("n").asText(null);
            String exponent = jwk.path("e").asText(null);
            if (modulus == null || exponent == null) {
                continue;
            }
            try {
                result.put(keyId, toRsaPublicKey(modulus, exponent));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                LOG.debugv("Skipping unparsable OIDC key {0}: {1}", keyId, e.getMessage());
            }
        }
        return result;
    }

    private JsonNode getJson(String url, String description) throws VerificationException {
        URI uri = validatePublicHttpsUri(url, description);
        HttpClientContext context = HttpClientContext.create();
        try {
            return httpClient.execute(ClassicRequestBuilder.get(uri).build(), context, response -> {
                if (response.getCode() >= 300 && response.getCode() < 400) {
                    throw new VerificationException("Redirects aren't allowed while fetching " + description);
                }
                if (response.getCode() != HttpStatus.SC_OK) {
                    throw new VerificationException("Fetching " + description + " returned HTTP " + response.getCode());
                }
                byte[] body = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
                try {
                    return objectMapper.readTree(body);
                } catch (Exception e) {
                    throw new VerificationException(description + " is not valid JSON", e);
                }
            });
        } catch (VerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new VerificationException("Failed to fetch " + description, e);
        }
    }

    static URI validatePublicHttpsUri(String value, String description) throws VerificationException {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new VerificationException("Invalid " + description + " URL", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null) {
            throw new VerificationException(description + " must use a public HTTPS URL");
        }
        String host = uri.getHost();
        boolean literalAddress = host.indexOf(':') >= 0 || host.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
        if (literalAddress && isBlockedPublicAddress(InetAddress.ofLiteral(host))) {
            throw new VerificationException(description + " resolves to a non-public address");
        }
        return uri;
    }

    private static RSAPublicKey toRsaPublicKey(String n, String e) throws GeneralSecurityException {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(pad(n)));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(pad(e)));
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    private static boolean signatureValid(String signingInput, String encodedSignature, RSAPublicKey key) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getUrlDecoder().decode(pad(encodedSignature)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private JsonNode decodeJson(String segment) throws VerificationException {
        try {
            JsonNode node = objectMapper.readTree(Base64.getUrlDecoder().decode(pad(segment)));
            if (node == null || !node.isObject()) {
                throw new VerificationException("Token header is not valid JSON");
            }
            return node;
        } catch (IllegalArgumentException | java.io.IOException e) {
            throw new VerificationException("Token header is not valid JSON", e);
        }
    }

    private static String pad(String value) {
        return switch (value.length() % 4) {
            case 2 -> value + "==";
            case 3 -> value + "=";
            default -> value;
        };
    }

    @PreDestroy
    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (Exception e) {
            LOG.debugv("Could not close Verified Permissions OIDC client: {0}", e.getMessage());
        }
    }

    private record CachedJwks(Map<String, RSAPublicKey> keysByKid, Instant fetchedAt) {
        boolean expired() {
            return Instant.now().isAfter(fetchedAt.plus(CACHE_TTL));
        }
    }

    private record PublicOnlyDnsResolver(DnsResolver delegate) implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = delegate.resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException("OIDC host has no addresses: " + host);
            }
            for (InetAddress address : addresses) {
                if (isBlockedPublicAddress(address)) {
                    throw new UnknownHostException("OIDC host resolves to a non-public address: " + host);
                }
            }
            return addresses.clone();
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }

    static boolean isBlockedPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return blockedIpv4(bytes, 0);
        }
        if (bytes.length == 16) {
            if (ipv4Mapped(bytes)) {
                return blockedIpv4(bytes, 12);
            }
            int first = Byte.toUnsignedInt(bytes[0]);
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20
                    && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            return uniqueLocal || documentation;
        }
        return true;
    }

    private static boolean ipv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean blockedIpv4(byte[] bytes, int offset) {
        int first = Byte.toUnsignedInt(bytes[offset]);
        int second = Byte.toUnsignedInt(bytes[offset + 1]);
        int third = Byte.toUnsignedInt(bytes[offset + 2]);
        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || first >= 224;
    }

    public static class VerificationException extends RuntimeException {
        public VerificationException(String message) {
            super(message);
        }

        public VerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
