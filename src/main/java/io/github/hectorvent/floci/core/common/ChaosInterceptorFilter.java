package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.ChaosConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.ChaosConfig.ChaosFaultConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.ChaosConfig.ChaosNetworkConfig;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import java.util.UUID;

/**
 * Injects faults and network conditions into inbound requests when
 * {@code floci.chaos.enabled = true} ({@code FLOCI_CHAOS_ENABLED=true} in the environment).
 * Every probability defaults to 0.0, so enabling the master switch alone changes nothing.
 *
 * <p>Runs at priority 6500 and deliberately not pre-matching, so {@link AwsProtocolClaimFilter}
 * (pre-matching, 6000) has already resolved the wire protocol onto
 * {@link AwsProtocolClaimFilter#CLAIM_PROPERTY}. The injected error is then shaped for that
 * protocol, because AWS SDKs hard-fail on the wrong shape: an XML parser blows up on a leading
 * <code>{</code> and a JSON parser on a leading {@code <}. The shapes mirror
 * {@link IamEnforcementFilter}'s builders.
 *
 * <p>One roll per condition, in this order:
 * <ul>
 *   <li>latency: sleeps {@code floci.chaos.network.latency-ms} and lets the request proceed</li>
 *   <li>no response: a bounded sleep standing in for a dropped connection, then a terminal
 *       timeout error so the resource never runs (see {@link #simulateNoResponse})</li>
 *   <li>throttle: aborts with the protocol's throttling error</li>
 *   <li>access denied: aborts with the protocol's access-denied error</li>
 *   <li>generic fault: aborts with throttling, the default injected fault</li>
 * </ul>
 *
 * <p>The sleeps block the serving thread on purpose, which is the point of a latency simulator.
 * Nothing here should ever be enabled in a deployment that is serving real work.
 */
@Provider
@Priority(6500)
public class ChaosInterceptorFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(ChaosInterceptorFilter.class);

    private static final String THROTTLE_MESSAGE = "Rate exceeded";
    private static final String ACCESS_DENIED_MESSAGE = "Access denied by Floci chaos injection";
    private static final String NO_RESPONSE_MESSAGE = "Request timed out (Floci chaos no-response injection)";
    private static final String ROUTE53_XML_NAMESPACE = AwsNamespaces.ROUTE53;

    /**
     * How much longer than the configured latency a "no response" waits. The stand-in is a long
     * sleep rather than a real hang: a JAX-RS filter that never returns holds its serving thread
     * for the lifetime of the process, which leaks the thread and eventually starves the server,
     * so the client has to be the one that gives up. The wait is bounded by
     * {@link #NO_RESPONSE_MAX_MS} regardless of the configured latency.
     */
    private static final long NO_RESPONSE_FACTOR = 60;
    private static final long NO_RESPONSE_DEFAULT_MS = 30_000;
    private static final long NO_RESPONSE_MAX_MS = 120_000;

    /**
     * An instance field, not a static one, so no {@code --initialize-at-run-time} entry is needed:
     * the provider is constructed at runtime, so its seed is never captured into the native image.
     * Used only for the unseeded (nondeterministic) mode; the seeded mode derives its rolls from
     * {@link #seededRoll} instead and never touches this stream.
     */
    private final Random random = new Random();

    /**
     * The decision points a roll is taken at. Each is a distinct salt so that, under a fixed seed,
     * a request's latency roll cannot alias its throttle roll: the two draw from independent
     * deterministic streams even though they share the same request key.
     */
    enum RollPoint {
        LATENCY,
        NO_RESPONSE,
        THROTTLE,
        ACCESS_DENIED,
        FAULT
    }

    // jakarta.inject.Provider qualified inline: this file imports jakarta.ws.rs.ext.Provider,
    // the JAX-RS annotation, and the two types collide. Resolved lazily for the same reason as
    // AwsProtocolClaimFilter: JAX-RS providers are instantiated before runtime config mappings
    // exist.
    private final jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    public ChaosInterceptorFilter(jakarta.inject.Provider<EmulatorConfig> configProvider) {
        this.configProvider = configProvider;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        ChaosConfig chaos = configProvider.get().chaos();
        if (!chaos.enabled()) {
            return;
        }
        if (isFlociInternalRoute(ctx)) {
            // Never fault Floci's own endpoints: the internal webhooks, the AWS helper routes and
            // the health check are not the emulated AWS surface the caller is testing, and a chaos
            // fault on them breaks the emulator itself rather than the workload under test.
            return;
        }
        ChaosNetworkConfig network = chaos.network();
        ChaosFaultConfig fault = chaos.fault();

        // In seeded mode the outcome is a pure function of (seed, request key, decision), so a
        // replay with the same seed and the same request sequence reproduces the same faults
        // regardless of how the requests interleave across threads. Unseeded mode keeps the
        // original behaviour: draws from a shared Random, nondeterministic under concurrency.
        Long seed = chaos.seed().orElse(null);
        String requestKey = seed == null ? null : requestKey(ctx);

        if (rolls(seed, requestKey, RollPoint.LATENCY, network.latencyProbability())) {
            LOG.debugv("Chaos: injecting {0}ms latency into {1} {2}",
                    network.latencyMs(), ctx.getMethod(), ctx.getUriInfo().getPath());
            sleep(network.latencyMs());
        }

        InjectedFault injected = selectFault(network, fault, seed, requestKey);
        if (injected == InjectedFault.NONE) {
            return;
        }
        LOG.debugv("Chaos: injecting {0} into {1} {2}",
                injected, ctx.getMethod(), ctx.getUriInfo().getPath());

        switch (injected) {
            case NO_RESPONSE -> ctx.abortWith(simulateNoResponse(network.latencyMs(), resolveShape(ctx)));
            case THROTTLE -> ctx.abortWith(throttlingResponse(resolveShape(ctx)));
            case ACCESS_DENIED -> ctx.abortWith(accessDeniedResponse(resolveShape(ctx)));
            case NONE -> {
                // Unreachable: returned above.
            }
        }
    }

    /**
     * The first condition whose roll fires, checked in the documented order. The generic
     * {@code fault-probability} maps to {@link InjectedFault#THROTTLE}, the default injected
     * fault, so a caller who only wants "something goes wrong sometimes" gets a retryable error.
     */
    private InjectedFault selectFault(ChaosNetworkConfig network, ChaosFaultConfig fault,
                                      Long seed, String requestKey) {
        if (rolls(seed, requestKey, RollPoint.NO_RESPONSE, network.noResponseProbability())) {
            return InjectedFault.NO_RESPONSE;
        }
        if (rolls(seed, requestKey, RollPoint.THROTTLE, fault.throttleProbability())) {
            return InjectedFault.THROTTLE;
        }
        if (rolls(seed, requestKey, RollPoint.ACCESS_DENIED, fault.accessDeniedProbability())) {
            return InjectedFault.ACCESS_DENIED;
        }
        if (rolls(seed, requestKey, RollPoint.FAULT, fault.faultProbability())) {
            return InjectedFault.THROTTLE;
        }
        return InjectedFault.NONE;
    }

    /**
     * Whether a roll at {@code probability} fires. When {@code seed} is {@code null} the draw is
     * nondeterministic (a shared {@link Random}); when it is set the draw is a pure function of
     * the seed, the request key and the decision point, so it reproduces on replay.
     */
    private boolean rolls(Long seed, String requestKey, RollPoint point, double probability) {
        if (probability <= 0.0) {
            return false;
        }
        if (probability >= 1.0) {
            return true;
        }
        double draw = seed == null ? random.nextDouble() : seededRoll(seed, requestKey, point);
        return draw < probability;
    }

    /**
     * A deterministic draw in {@code [0, 1)} for one decision point of one request under a seed.
     * SHA-256 over {@code seed | request key | decision} gives a value that depends on all three
     * and nothing else, so concurrent requests never contend for a shared stream and a replay with
     * the same inputs yields the same draw. The top 53 bits of the digest are scaled to a double,
     * matching {@link Random#nextDouble}'s resolution.
     */
    static double seededRoll(long seed, String requestKey, RollPoint point) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(seed).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(requestKey.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(point.name().getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            long bits = 0L;
            for (int i = 0; i < 8; i++) {
                bits = (bits << 8) | (hash[i] & 0xFFL);
            }
            // Top 53 bits to a double in [0, 1), the same construction as Random#nextDouble.
            return (bits >>> 11) * 0x1.0p-53;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM, so this is unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable for seeded chaos", e);
        }
    }

    /**
     * A stable identity for the request, used only in seeded mode so replays line up. Preference
     * order: the SDK's {@code amz-sdk-invocation-id} (a UUID the SDK keeps constant across retries
     * of the same logical call, so a retried request re-rolls identically), then a composite of
     * method, path, caller access key and SigV4 signature. The signature ties the key to the exact
     * signed request without being a secret we log. Never null in seeded mode.
     */
    private static String requestKey(ContainerRequestContext ctx) {
        String invocationId = ctx.getHeaderString("amz-sdk-invocation-id");
        if (invocationId != null && !invocationId.isEmpty()) {
            return invocationId;
        }
        String signature = sigV4Signature(ctx);
        return ctx.getMethod()
                + ' ' + ctx.getUriInfo().getPath()
                + '|' + (signature != null ? signature : "");
    }

    /** The {@code Signature=} value from the SigV4 Authorization header, or null when unsigned. */
    private static String sigV4Signature(ContainerRequestContext ctx) {
        String auth = ctx.getHeaderString("Authorization");
        if (auth == null) {
            return null;
        }
        int idx = auth.indexOf("Signature=");
        if (idx < 0) {
            return null;
        }
        String tail = auth.substring(idx + "Signature=".length());
        int comma = tail.indexOf(',');
        return comma < 0 ? tail : tail.substring(0, comma);
    }

    /**
     * Stands in for a dropped connection: holds the request long past any client timeout, then
     * ABORTS it so the resource never runs. Aborting rather than proceeding is deliberate. If the
     * request were allowed through after the sleep, a client that already timed out and retried a
     * mutating request would have the delayed original also perform its mutation, causing
     * duplicate effects. The bounded sleep still simulates the client-visible hang (a real
     * never-returning filter would leak the serving thread; see {@link #NO_RESPONSE_FACTOR}), and
     * the terminal error is shaped for the caller's protocol so an SDK that is still waiting can
     * parse the timeout rather than choke on an unexpected body.
     */
    private static Response simulateNoResponse(long latencyMs, ErrorShape shape) {
        long requested = latencyMs > 0 ? latencyMs * NO_RESPONSE_FACTOR : NO_RESPONSE_DEFAULT_MS;
        sleep(Math.min(requested, NO_RESPONSE_MAX_MS));
        return timeoutResponse(shape);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            LOG.debugv(e, "Chaos: sleep of {0}ms interrupted", millis);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Floci's own endpoints, which chaos must never fault because they are the emulator's control
     * surface, not the emulated AWS surface under test: the {@code /_floci/*} internal webhooks and
     * assets, the {@code /_aws/*} helper routes (execute-api, SNS/SQS/SES/Kinesis callbacks) and the
     * exact {@code /health} check. The match on {@code /health} is exact so it does not also catch
     * Route 53's {@code /healthcheck} data-plane resource, which is a real emulated AWS surface.
     */
    static boolean isFlociInternalRoute(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path == null) {
            return false;
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.startsWith("/_floci/")
                || normalized.startsWith("/_aws/")
                || normalized.equals("/health");
    }

    /**
     * The error shape this request's caller can parse. S3 is detected first, keyed off the signing
     * service in either the {@code Authorization} header or, for a presigned request, the
     * {@code X-Amz-Credential} query parameter (which is where a presigned request carries its
     * credential scope, absent from any header). Both the {@code s3} scope and the
     * {@code s3express} scope map to S3 XML. CBOR services (CloudWatch defaults to
     * smithy-rpc-v2-cbor) and rest-xml services (Route 53) get their own shapes, because a JSON
     * body is unparseable by those SDKs. Otherwise the shape comes from the protocol claim,
     * falling back to the form-encoded sniff when no claim was made.
     */
    static ErrorShape resolveShape(ContainerRequestContext ctx) {
        if (isS3SigningScope(signingService(ctx))) {
            return ErrorShape.S3_XML;
        }
        if (ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                && claim.protocol() != null) {
            if (claim.protocol() == WireProtocol.AWS_QUERY) {
                return ErrorShape.QUERY_XML;
            }
            if (claim.protocol() == WireProtocol.RPCV2_CBOR
                    || claim.protocol() == WireProtocol.AWS_CBOR_TARGET) {
                return ErrorShape.CBOR;
            }
            if (claim.service() != null && isS3SigningScope(claim.service().externalKey())) {
                return ErrorShape.S3_XML;
            }
            if (claim.service() != null && isRestXmlService(claim.service().externalKey())) {
                return ErrorShape.REST_XML;
            }
            return ErrorShape.JSON;
        }
        return isFormEncoded(ctx.getMediaType()) ? ErrorShape.QUERY_XML : ErrorShape.JSON;
    }

    /** The SigV4 signing service, from the Authorization header or a presigned X-Amz-Credential. */
    private static String signingService(ContainerRequestContext ctx) {
        return SigV4CredentialScope.serviceName(ctx.getHeaderString("Authorization"))
                .or(() -> SigV4CredentialScope.serviceNameFromCredential(
                        ctx.getUriInfo().getQueryParameters().getFirst("X-Amz-Credential")))
                .orElse(null);
    }

    /** S3 and S3 Express both answer with S3 XML; both sign under an S3-family scope. */
    private static boolean isS3SigningScope(String service) {
        return "s3".equals(service) || "s3express".equals(service);
    }

    /** Route 53 speaks rest-xml, so its errors are namespaced XML rather than JSON. */
    private static boolean isRestXmlService(String service) {
        return "route53".equals(service);
    }

    private static boolean isFormEncoded(MediaType mediaType) {
        return mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype());
    }

    /** S3 answers a throttle with {@code SlowDown} and 503, every other protocol with a 400. */
    static Response throttlingResponse(ErrorShape shape) {
        if (shape == ErrorShape.S3_XML) {
            return errorFor(shape, "SlowDown", THROTTLE_MESSAGE, 503);
        }
        String code = shape == ErrorShape.JSON ? "ThrottlingException" : "Throttling";
        return errorFor(shape, code, THROTTLE_MESSAGE, 400);
    }

    static Response accessDeniedResponse(ErrorShape shape) {
        String code = shape == ErrorShape.JSON ? "AccessDeniedException" : "AccessDenied";
        return errorFor(shape, code, ACCESS_DENIED_MESSAGE, 403);
    }

    /**
     * The terminal error for a simulated no-response, once the bounded hang has elapsed. S3 uses
     * its {@code RequestTimeout} (400); other protocols use a 504 gateway-timeout-style error, the
     * closest retryable shape their SDKs recognise for "the request did not complete in time".
     */
    static Response timeoutResponse(ErrorShape shape) {
        int status = shape == ErrorShape.S3_XML ? 400 : 504;
        String code = shape == ErrorShape.JSON ? "RequestTimeoutException" : "RequestTimeout";
        return errorFor(shape, code, NO_RESPONSE_MESSAGE, status);
    }

    /** Builds the injected error in the wire shape the caller's SDK can parse. */
    private static Response errorFor(ErrorShape shape, String code, String message, int status) {
        return switch (shape) {
            case QUERY_XML -> queryXmlError(code, message, status);
            case JSON -> jsonError(code, message, status);
            case S3_XML -> s3XmlError(code, message, status);
            case REST_XML -> restXmlError(code, message, status);
            case CBOR -> CborErrorResponses.of(new AwsException(code, message, status),
                    CborErrorResponses.GENERIC_CBOR_MEDIA_TYPE);
        };
    }

    private static Response queryXmlError(String code, String message, int status) {
        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", code)
                    .elem("Message", message)
                  .end("Error")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(status).type(MediaType.APPLICATION_XML).entity(xml).build();
    }

    private static Response s3XmlError(String code, String message, int status) {
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Error")
                  .elem("Code", code)
                  .elem("Message", message)
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("Error")
                .build();
        return Response.status(status).type(MediaType.APPLICATION_XML).entity(xml).build();
    }

    /**
     * The rest-xml error shape (Route 53), a namespaced {@code ErrorResponse} mirroring
     * {@code Route53Controller}'s own error, so a rest-xml SDK parses the injected fault.
     */
    private static Response restXmlError(String code, String message, int status) {
        String xml = new XmlBuilder()
                .start("ErrorResponse", ROUTE53_XML_NAMESPACE)
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", code)
                    .elem("Message", message)
                  .end("Error")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(status).type(MediaType.APPLICATION_XML).entity(xml).build();
    }

    private static Response jsonError(String code, String message, int status) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header("x-amzn-query-error", code + ";Sender")
                // rest-json SDKs resolve the error code from this header before the body __type
                .header("X-Amzn-Errortype", code)
                .entity(new AwsErrorResponse(code, message))
                .build();
    }

    private enum InjectedFault {
        NONE,
        NO_RESPONSE,
        THROTTLE,
        ACCESS_DENIED
    }

    enum ErrorShape {
        QUERY_XML,
        JSON,
        S3_XML,
        REST_XML,
        CBOR
    }
}
