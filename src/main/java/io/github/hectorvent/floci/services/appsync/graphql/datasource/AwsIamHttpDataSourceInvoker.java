package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.quarkus.vertx.http.HttpServer;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Signs audited, same-account AWS operations for Floci's actual local listener, never for an external URL. */
@ApplicationScoped
public class AwsIamHttpDataSourceInvoker {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final IamService iam;
    private final ServiceRoleAuthorizer authorizer;
    private final ServiceRegistry services;
    private final RegionResolver regions;
    private final EmulatorConfig config;
    private final HttpServer server;
    // This transport has no public-DNS filter: its URL is exclusively the runtime-owned listener.
    private final HttpDataSourceTransport local;

    @Inject
    public AwsIamHttpDataSourceInvoker(IamService iam, ServiceRoleAuthorizer authorizer,
                                       ServiceRegistry services, RegionResolver regions,
                                       EmulatorConfig config, HttpServer server) {
        this(iam, authorizer, services, regions, config, server,
                new HttpDataSourceTransport(HttpDataSourceTransport.client(SystemDefaultDnsResolver.INSTANCE)));
    }

    AwsIamHttpDataSourceInvoker(IamService iam, ServiceRoleAuthorizer authorizer,
                                ServiceRegistry services, RegionResolver regions,
                                EmulatorConfig config, HttpServer server, HttpDataSourceTransport local) {
        this.iam = iam;
        this.authorizer = authorizer;
        this.services = services;
        this.regions = regions;
        this.config = config;
        this.server = server;
        this.local = local;
    }

    public Map<String, Object> invoke(DataSource source, HttpDataSourceRequest request, String apiRegion) {
        HttpDataSourceAwsTarget target = HttpDataSourceAwsTarget.parse(source, request, apiRegion, regions);
        // A late request after cleanup must be rejected as an unknown credential, even when the
        // originating HTTP call timed out. Permissive IAM mode cannot give that guarantee.
        if (!config.services().iam().enforcementEnabled()) {
            throw new AwsException("AccessDeniedException", "Signed HTTP data sources require IAM enforcement enabled", 403);
        }
        String serviceId = "states".equals(target.service()) ? "stepfunctions" : "scheduler";
        if (!services.isServiceEnabled(serviceId)) {
            throw new AwsException("ServiceNotAvailableException", "Service " + serviceId + " is not enabled", 400);
        }
        authorizer.refusal(target.roleArn(), target.accountId(), ServicePrincipals.of("appsync"),
                target.sourceApiArn(), List.of()).ifPresent(reason -> {
            throw new AwsException("AccessDeniedException", reason, 403);
        });
        URI listener = server.getLocalBaseUri();
        if (listener == null) {
            throw new AwsException("InternalFailureException", "The local AWS listener is not ready", 500);
        }
        String path = request.uri().getRawPath();
        String query = request.uri().getRawQuery();
        URI uri = listener.resolve(path + (query == null ? "" : "?" + query));
        String key = accessKey();
        String secret = randomToken(32);
        String token = randomToken(48);
        try {
            iam.registerSessionForAccount(target.accountId(), key, secret, token, target.roleArn(),
                    Instant.now().plusSeconds(120), null);
            return local.send(signed(request, uri, target, key, secret, token));
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("HttpException", "The signed HTTP data source request could not complete", 500);
        } finally {
            iam.unregisterSession(target.accountId(), key);
        }
    }

    private static HttpDataSourceRequest signed(HttpDataSourceRequest request, URI uri, HttpDataSourceAwsTarget target,
                                                 String key, String secret, String token) throws Exception {
        TreeMap<String, String> headers = new TreeMap<>(request.headers());
        headers.putIfAbsent("content-type", "states".equals(target.service())
                ? "application/x-amz-json-1.0" : "application/json");
        String stamp = AMZ_DATE.format(Instant.now());
        headers.put("host", uri.getRawAuthority());
        headers.put("x-amz-date", stamp);
        headers.put("x-amz-security-token", token);
        String signedHeaders = String.join(";", headers.keySet());
        CredentialScope scope = new CredentialScope(key, stamp.substring(0, 8), target.region(), target.service());
        String canonical = request.method() + "\n" + uri.getRawPath() + "\n"
                + SigV4Canonicalization.canonicalQueryString(uri.getRawQuery(), false) + "\n"
                + SigV4Canonicalization.canonicalHeaders(signedHeaders, headers::get) + "\n"
                + signedHeaders + "\n" + Digests.sha256Hex(request.body());
        String toSign = "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope.credentialScope() + "\n" + Digests.sha256Hex(canonical);
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey(secret, scope.date(), target.region(), target.service()), toSign));
        headers.put("authorization", "AWS4-HMAC-SHA256 Credential=" + key + "/" + scope.credentialScope()
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return new HttpDataSourceRequest(uri, request.method(), headers, request.body());
    }

    private static String accessKey() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder key = new StringBuilder("ASIA");
        for (int i = 0; i < 16; i++) {
            key.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return key.toString();
    }

    private static String randomToken(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @PreDestroy
    void close() {
        local.close();
    }
}
