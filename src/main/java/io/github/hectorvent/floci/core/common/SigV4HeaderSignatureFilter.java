package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4AuthorizationHeader;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayAwsExecuteController;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayExecuteController;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayUserRequestController;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncExecutionController;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3ControlController;
import io.github.hectorvent.floci.services.s3.S3Controller;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Verifies the SigV4 signature in the {@code Authorization} header of every request outside S3
 * when {@code floci.auth.validate-signatures} is enabled, so a request signed with the wrong
 * secret is refused by SQS, KMS, IAM, Scheduler and the rest as it already is by S3.
 *
 * <p>The canonical request is rebuilt from the request as it arrived, by the rules
 * {@link SigV4Canonicalization} shares with {@code ExecuteApiSigV4Authorizer}: the wire path with
 * each segment URI-encoded a second time (and the path as sent, for a signer that encodes once),
 * the canonical query string, the headers named in {@code SignedHeaders}, and the SHA-256 of the
 * body. The signature is derived with the key's secret and the credential scope, and the two are
 * compared in constant time.
 *
 * <p>Runs after matching, on the worker thread a blocking resource method runs on, because hashing
 * the body is a blocking read that the I/O thread a pre-matching filter runs on does not allow. By
 * then pre-matching filters may have rewritten the request, so the path and query come from the
 * Vert.x request as it arrived rather than from {@code UriInfo}. The one body rewrite, the
 * {@code QueueUrl} that {@link SqsQueueUrlRouterFilter} appends to an SDK v1 Query call sent to a
 * queue URL, is reported by that filter and left out of the hash and the {@code content-length}.
 * The one header rewrite, the CBOR {@code Content-Type} that {@link AwsCborContentTypeFilter}
 * normalizes, is read back from the header that filter preserves it in, which it strips from
 * every inbound request first so a client cannot supply it.
 *
 * <p>Which requests another verifier owns is decided by the resource the request matched, never by
 * the service the caller's own credential scope names: a request for {@link S3Controller} goes
 * through {@code S3HeaderSignatureFilter}, one for the API Gateway execute controllers through
 * {@code ExecuteApiSigV4Authorizer}, which decides per method whether IAM auth applies at all, and
 * one for {@code AppSyncExecutionController} (GraphQL) through AppSync's {@code IamAuthValidator},
 * which answers in the {@code UnauthorizedException} envelope AppSync clients parse.
 * Everything else is verified here, S3 Control included, whatever service its scope names.
 *
 * <p>As with S3, signature verification is authentication only, and an unsigned request is let
 * through: whether a caller may act is IAM enforcement's decision. Presigned query-string
 * signatures and SigV4a ({@code AWS4-ECDSA-P256-SHA256}) are not verified here.
 *
 * <p>Errors follow the protocol the request used: a Query or REST-XML request (Route 53,
 * CloudFront, S3 Control) receives an {@code <ErrorResponse>}, a CBOR request a CBOR body, and
 * everything else JSON, which REST-JSON clients read through {@code X-Amzn-Errortype}. The codes
 * are AWS's: {@code IncompleteSignature} for a header that cannot be checked,
 * {@code InvalidClientTokenId} / {@code UnrecognizedClientException} for an access key Floci does
 * not know, and {@code SignatureDoesNotMatch} / {@code InvalidSignatureException} for a signature
 * that does not verify or falls outside the fifteen-minute clock-skew window.
 *
 * <p>Nothing here runs with the flag off: the filter returns before reading a single header.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
@ApplicationScoped
public class SigV4HeaderSignatureFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(SigV4HeaderSignatureFilter.class);

    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(15);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** Resources whose signatures another verifier owns; see the class comment. */
    private static final Set<Class<?>> VERIFIED_ELSEWHERE = Set.of(S3Controller.class,
            ApiGatewayExecuteController.class, ApiGatewayAwsExecuteController.class,
            ApiGatewayUserRequestController.class, AppSyncExecutionController.class);

    /** REST-XML resources the catalog does not list: S3 Control answers in S3's XML. */
    private static final Set<Class<?>> UNCATALOGUED_REST_XML = Set.of(S3ControlController.class);

    /** The well-known local-dev pair, honoured as every other SigV4 check in Floci honours it. */
    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final String LEGACY_SECRET_KEY = "test";

    private static final String MISMATCH_MESSAGE = "The request signature we calculated does not match "
            + "the signature you provided. Check your AWS Secret Access Key and signing method. "
            + "Consult the service documentation for details.";
    private static final String INVALID_TOKEN_MESSAGE = "The security token included in the request is invalid.";

    // Lazily resolved: JAX-RS providers are instantiated before runtime config mappings exist
    // (the AwsProtocolClaimFilter pattern).
    private final jakarta.inject.Provider<EmulatorConfig> configProvider;
    private final IamService iamService;
    private final ResolvedServiceCatalog catalog;
    private final CurrentVertxRequest currentVertxRequest;

    @Context
    ResourceInfo resourceInfo;

    @Inject
    public SigV4HeaderSignatureFilter(jakarta.inject.Provider<EmulatorConfig> configProvider,
                                      IamService iamService, ResolvedServiceCatalog catalog,
                                      CurrentVertxRequest currentVertxRequest) {
        this.configProvider = configProvider;
        this.iamService = iamService;
        this.catalog = catalog;
        this.currentVertxRequest = currentVertxRequest;
    }

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        if (!configProvider.get().auth().validateSignatures()) {
            return;
        }
        Class<?> resource = resourceInfo != null ? resourceInfo.getResourceClass() : null;
        if (resource != null && VERIFIED_ELSEWHERE.contains(resource)) {
            return;
        }
        SigV4AuthorizationHeader signed = SigV4AuthorizationHeader.parse(ctx.getHeaderString("Authorization"));
        if (signed == null) {
            return;
        }

        CredentialScope scope = CredentialScope.parse(signed.credential());
        if (scope == null || isBlank(signed.signedHeaders()) || isBlank(signed.signature())) {
            incompleteSignature(ctx, resource, "Authorization header requires 'Credential' (in the form "
                    + "<access key>/<date>/<region>/<service>/aws4_request), 'SignedHeaders' and "
                    + "'Signature' parameters.");
            return;
        }
        if (!SigV4RequestValidator.containsHeader(signed.signedHeaders(), "host")) {
            incompleteSignature(ctx, resource,
                    "'Host' or ':authority' must be a 'SignedHeader' in the AWS Authorization.");
            return;
        }
        Instant requestTime = requestTime(ctx);
        if (requestTime == null) {
            incompleteSignature(ctx, resource, "Authorization header requires existence of either a "
                    + "'X-Amz-Date' or a 'Date' header.");
            return;
        }

        Optional<String> secretKey = resolveSecretKey(scope.accessKeyId(), ctx.getHeaderString("X-Amz-Security-Token"));
        if (secretKey.isEmpty()) {
            LOG.debugv("Refusing {0} request signed with unknown access key {1}",
                    scope.service(), SigV4RequestValidator.sanitizeForLog(scope.accessKeyId()));
            abort(ctx, resource, 403, "InvalidClientTokenId", "UnrecognizedClientException", INVALID_TOKEN_MESSAGE);
            return;
        }

        String amzDate = AMZ_DATE.format(requestTime);
        Instant now = Instant.now();
        if (Duration.between(requestTime, now).abs().compareTo(MAX_CLOCK_SKEW) > 0) {
            signatureDoesNotMatch(ctx, resource, "Signature expired: " + amzDate + " is outside the "
                    + MAX_CLOCK_SKEW.toMinutes() + " minute window around the server time "
                    + AMZ_DATE.format(now) + ".");
            return;
        }
        if (!amzDate.startsWith(scope.date())) {
            signatureDoesNotMatch(ctx, resource, "Date in Credential scope does not match YYYYMMDD from "
                    + "ISO-8601 version of date from HTTP: '" + scope.date() + "' != '"
                    + amzDate.substring(0, 8) + "'.");
            return;
        }

        byte[] body = signedBody(ctx);
        UnaryOperator<String> headers = name -> "content-length".equals(name)
                && ctx.getProperty(SqsQueueUrlRouterFilter.APPENDED_BODY_BYTES_PROPERTY) != null
                ? String.valueOf(body.length)
                : headerValue(ctx, name);
        boolean matches;
        try {
            matches = signatureMatches(ctx.getMethod(), rawPath(ctx), rawQuery(ctx), signed.signedHeaders(),
                    headers, body, secretKey.get(), amzDate, scope, signed.signature());
        } catch (Exception e) {
            LOG.debugv(e, "SigV4 verification failed to complete for accessKey={0}",
                    SigV4RequestValidator.sanitizeForLog(scope.accessKeyId()));
            matches = false;
        }
        if (!matches) {
            LOG.debugv("Refusing {0} request: signature mismatch for accessKey={1}",
                    scope.service(), SigV4RequestValidator.sanitizeForLog(scope.accessKeyId()));
            signatureDoesNotMatch(ctx, resource, MISMATCH_MESSAGE);
        }
    }

    /** Whether {@code signature} verifies for this request; package-private for the unit test. */
    static boolean signatureMatches(String method, String rawPath, String rawQuery, String signedHeaders,
                                    UnaryOperator<String> headers, byte[] body, String secretKey,
                                    String amzDate, CredentialScope scope, String signature) throws Exception {
        return SigV4Canonicalization.signatureMatches(method, rawPath,
                SigV4Canonicalization.canonicalQueryString(rawQuery, false),
                SigV4Canonicalization.canonicalHeaders(signedHeaders, headers),
                signedHeaders,
                SigV4Canonicalization.payloadHash(headers.apply("x-amz-content-sha256"), signedHeaders, body, false),
                amzDate, scope, secretKey, signature);
    }

    /**
     * The path as the client sent it, before any filter rewrote it or JAX-RS normalized it. Falls
     * back to the request URI when no Vert.x request is current.
     */
    private String rawPath(ContainerRequestContext ctx) {
        HttpServerRequest request = vertxRequest();
        if (request != null && request.path() != null && !request.path().isEmpty()) {
            return request.path();
        }
        return ctx.getUriInfo().getRequestUri().getRawPath();
    }

    /** The query string as the client sent it, with the same fallback as {@link #rawPath}. */
    private String rawQuery(ContainerRequestContext ctx) {
        HttpServerRequest request = vertxRequest();
        if (request != null) {
            return request.query();
        }
        return ctx.getUriInfo().getRequestUri().getRawQuery();
    }

    private HttpServerRequest vertxRequest() {
        RoutingContext routingContext = currentVertxRequest != null ? currentVertxRequest.getCurrent() : null;
        return routingContext != null ? routingContext.request() : null;
    }

    /**
     * A signed header's value as the client sent it, repeated values joined with commas as SigV4
     * specifies. {@code content-type} is the one {@link AwsCborContentTypeFilter} replaced, when it
     * replaced it. {@code host} falls back to the request URI's authority for a request that carried
     * no {@code Host} header (HTTP/2, or HTTP/1.0 handled by {@link MissingHostHeaderFilter}).
     */
    private static String headerValue(ContainerRequestContext ctx, String name) {
        if ("content-type".equals(name)) {
            return SigV4RequestValidator.normalizeHeaderValue(requestContentType(ctx));
        }
        List<String> values = null;
        for (Map.Entry<String, List<String>> entry : ctx.getHeaders().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                values = entry.getValue();
                break;
            }
        }
        if (values != null && !values.isEmpty()) {
            List<String> normalized = new ArrayList<>(values.size());
            for (String value : values) {
                normalized.add(SigV4RequestValidator.normalizeHeaderValue(value));
            }
            return String.join(",", normalized);
        }
        if ("host".equals(name)) {
            URI requestUri = ctx.getUriInfo().getRequestUri();
            return RequestHost.of(null, requestUri);
        }
        return "";
    }

    /**
     * Reads the body and hands an identical stream back to the request, returning the part the
     * client signed: all of it, less any bytes {@link SqsQueueUrlRouterFilter} appended. The stream
     * is read directly rather than gated on {@code hasEntity()}, which is false for a request
     * without a {@code Content-Type}.
     */
    private static byte[] signedBody(ContainerRequestContext ctx) throws IOException {
        InputStream entity = ctx.getEntityStream();
        byte[] body = entity != null ? entity.readAllBytes() : new byte[0];
        ctx.setEntityStream(new ByteArrayInputStream(body));
        if (ctx.getProperty(SqsQueueUrlRouterFilter.APPENDED_BODY_BYTES_PROPERTY) instanceof Integer appended
                && appended <= body.length) {
            return Arrays.copyOf(body, body.length - appended);
        }
        return body;
    }

    private Optional<String> resolveSecretKey(String accessKeyId, String sessionToken) {
        if (LEGACY_ACCESS_KEY_ID.equals(accessKeyId)) {
            return Optional.of(LEGACY_SECRET_KEY);
        }
        return iamService.findSecretKey(accessKeyId, sessionToken);
    }

    /**
     * The request timestamp the client signed: {@code x-amz-date} in ISO 8601 basic form, or the
     * HTTP {@code Date} header when the client signed that instead.
     */
    private static Instant requestTime(ContainerRequestContext ctx) {
        String amzDate = ctx.getHeaderString("x-amz-date");
        try {
            if (amzDate != null && !amzDate.isBlank()) {
                return Instant.from(AMZ_DATE.parse(amzDate.trim()));
            }
            String httpDate = ctx.getHeaderString("Date");
            if (httpDate != null && !httpDate.isBlank()) {
                return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(httpDate.trim()));
            }
        } catch (DateTimeParseException e) {
            return null;
        }
        return null;
    }

    private void incompleteSignature(ContainerRequestContext ctx, Class<?> resource, String message) {
        abort(ctx, resource, 400, "IncompleteSignature", "IncompleteSignatureException", message);
    }

    private void signatureDoesNotMatch(ContainerRequestContext ctx, Class<?> resource, String message) {
        abort(ctx, resource, 403, "SignatureDoesNotMatch", "InvalidSignatureException", message);
    }

    /**
     * Refuses the request in the encoding it used. Query and REST-XML services name these failures
     * without the {@code Exception} suffix and JSON services with it, the split
     * {@code IamEnforcementFilter} already makes for an unknown key. A REST request carries no claim
     * that names its protocol, so REST-XML is read from the matched resource's catalog entry; the
     * other protocols from the request's content type, as {@code AccountContextFilter} reads it.
     */
    private void abort(ContainerRequestContext ctx, Class<?> resource, int status, String queryCode,
                       String jsonCode, String message) {
        String contentType = requestContentType(ctx);
        if (isCbor(ctx, contentType)) {
            ctx.abortWith(CborErrorResponses.of(new AwsException(jsonCode, message, status),
                    CborErrorResponses.mediaTypeFor(contentType)));
            return;
        }
        if (isFormEncoded(ctx.getMediaType())) {
            ctx.abortWith(AwsQueryResponse.error(queryCode, message, null, status));
            return;
        }
        if (isRestXml(resource)) {
            ctx.abortWith(AwsQueryResponse.error(queryCode, message, xmlNamespace(resource), status));
            return;
        }
        ctx.abortWith(AwsProtocolClaimFilter.errorResponse(status, jsonCode, message));
    }

    /** Whether the matched resource serves a REST-XML API. */
    private boolean isRestXml(Class<?> resource) {
        if (resource == null) {
            return false;
        }
        return UNCATALOGUED_REST_XML.contains(resource) || catalog.byResourceClass(resource)
                .map(service -> service.defaultProtocol() == ServiceProtocol.REST_XML)
                .orElse(false);
    }

    private String xmlNamespace(Class<?> resource) {
        return catalog.byResourceClass(resource).map(ServiceDescriptor::xmlNamespace).orElse(null);
    }

    /**
     * The {@code Content-Type} the client sent, before {@link AwsCborContentTypeFilter} normalized it.
     * That filter strips a client-sent copy of the header, so a value here is always its own.
     */
    private static String requestContentType(ContainerRequestContext ctx) {
        String original = ctx.getHeaderString(AwsCborContentTypeFilter.ORIGINAL_CONTENT_TYPE_HEADER);
        return original != null ? original : ctx.getHeaderString("Content-Type");
    }

    private static boolean isCbor(ContainerRequestContext ctx, String contentType) {
        return (contentType != null && contentType.toLowerCase().contains("cbor"))
                || "rpc-v2-cbor".equalsIgnoreCase(ctx.getHeaderString("smithy-protocol"));
    }

    private static boolean isFormEncoded(MediaType mediaType) {
        return mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
