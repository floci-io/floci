package io.floci.aws.core.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies the wire protocol of an inbound request per the Smithy
 * wire-protocol-selection guide: protocols are checked in precision order
 * (rpcv2Cbor, rpcv2Json, awsJson1_0, awsJson1_1, awsQuery, then REST) and the
 * first protocol whose "identification for claiming" signals match claims the
 * request.
 * <p>
 * Claiming uses non-payload signals only (method, path, headers, query parameters) so it is safe
 * to run in a pre-matching filter without touching the entity stream. As a
 * consequence the awsQuery claim covers POST {@code /} with the form
 * content type and the signed Query GET requests the dispatcher accepts.
 * The spec's body-level {@code Action}/{@code Version} checks
 * stay downstream in {@code AwsQueryController}, which already rejects a
 * missing {@code Action} with the AWS {@code MissingAction} error. Nothing
 * below awsQuery in the precision order claims such requests, so the narrowed
 * claim is decision-equivalent.
 * <p>
 * An empty result means the request carries an RPC signal that no supported
 * protocol claims — per the guide such input must be rejected (enforced only
 * when {@code floci.protocols.strict-claiming} is enabled). With it disabled the
 * request is served, and {@link #routeClaim} claims it by the route it reaches.
 * <p>
 * Intentional deviation from pure precedence iteration: a present
 * Smithy-Protocol header is treated as an exclusive rpcv2 signal. If its value
 * is unknown or the method/path does not match the rpcv2 shape, the request is
 * unclaimable rather than falling through to lower-precedence protocols. No
 * real client sends the header accidentally, and failing loud beats silently
 * serving a protocol the client did not ask for.
 *
 * @see <a href="https://smithy.io/2.0/guides/wire-protocol-selection.html">Smithy wire protocol selection</a>
 */
@ApplicationScoped
public class ProtocolClaimer {

    static final String SMITHY_PROTOCOL_HEADER = "Smithy-Protocol";
    static final String SMITHY_RPC_V2_CBOR = "rpc-v2-cbor";
    static final String SMITHY_RPC_V2_JSON = "rpc-v2-json";

    /**
     * The rpcv2 spec routes on the last four path segments, so an arbitrary
     * prefix before {@code /service/{serviceName}/operation/{operationName}}
     * must be tolerated. The {serviceName} segment is the Smithy service shape
     * name, which for AWS services equals the X-Amz-Target prefix without its
     * trailing dot.
     */
    private static final Pattern RPC_V2_PATH = Pattern.compile("(?:.*/)?service/([^/]+)/operation/([^/]+)/?$");
    private static final String CONTENT_TYPE_JSON_1_0 = "application/x-amz-json-1.0";
    private static final String CONTENT_TYPE_JSON_1_1 = "application/x-amz-json-1.1";
    private static final String CONTENT_TYPE_FORM_URLENCODED = "application/x-www-form-urlencoded";
    private static final String CONTENT_TYPE_CBOR = "application/cbor";
    private static final String CONTENT_TYPE_CBOR_1_1 = "application/x-amz-cbor-1.1";

    private final ResolvedServiceCatalog catalog;

    @Inject
    public ProtocolClaimer(ResolvedServiceCatalog catalog) {
        this.catalog = catalog;
    }

    public Optional<ProtocolClaim> claim(ClaimSignals signals) {
        boolean post = "POST".equalsIgnoreCase(signals.method());
        String mediaType = baseMediaType(signals.contentType());
        String path = signals.path();
        Matcher rpcV2Path = RPC_V2_PATH.matcher(path == null ? "" : path);
        boolean rpcV2PathMatches = rpcV2Path.matches();

        String smithy = signals.smithyProtocol() == null ? null : signals.smithyProtocol().trim();
        if (smithy != null && !smithy.isEmpty()) {
            if (!post || !rpcV2PathMatches) {
                return Optional.empty();
            }
            if (SMITHY_RPC_V2_CBOR.equals(smithy)) {
                return Optional.of(rpcV2Claim(WireProtocol.RPCV2_CBOR, rpcV2Path));
            }
            if (SMITHY_RPC_V2_JSON.equals(smithy)) {
                return Optional.of(rpcV2Claim(WireProtocol.RPCV2_JSON, rpcV2Path));
            }
            return Optional.empty();
        }

        // Header-less rpcv2Cbor carve-out: pre-header SDK releases and existing
        // clients post CBOR to the rpcv2 path without the Smithy-Protocol header.
        if (post && rpcV2PathMatches && isCbor(mediaType)) {
            return Optional.of(rpcV2Claim(WireProtocol.RPCV2_CBOR, rpcV2Path));
        }

        if ("GET".equalsIgnoreCase(signals.method()) && isRootPath(path)
                && isQueryGet(signals)) {
            String authorization = IamEnforcementFilter.requestAuthorization(
                    signals.authorization(), signals.queryParameters());
            ServiceDescriptor service = SigV4CredentialScope.serviceName(authorization)
                    .flatMap(catalog::byCredentialScope)
                    .orElseGet(() -> catalog.byCredentialScope("monitoring").orElse(null));
            return Optional.of(new ProtocolClaim(WireProtocol.AWS_QUERY, service,
                    AwsQueryServiceResolver.action(signals.queryParameters()), null));
        }

        if (post && isRootPath(path)) {
            String target = signals.xAmzTarget();
            if (target != null) {
                if (CONTENT_TYPE_JSON_1_0.equals(mediaType)) {
                    return Optional.of(targetClaim(WireProtocol.AWS_JSON_1_0, target));
                }
                if (CONTENT_TYPE_JSON_1_1.equals(mediaType)) {
                    return Optional.of(targetClaim(WireProtocol.AWS_JSON_1_1, target));
                }
                if ("application/json".equals(mediaType) && catalog.matchPlainJsonTarget(target).isPresent()) {
                    return Optional.of(targetClaim(WireProtocol.AWS_JSON_1_0, target));
                }
                if (isCbor(mediaType)) {
                    return Optional.of(targetClaim(WireProtocol.AWS_CBOR_TARGET, target));
                }
                return Optional.empty();
            }
            if (CONTENT_TYPE_FORM_URLENCODED.equals(mediaType)) {
                return Optional.of(queryClaim(signals));
            }
        }

        return Optional.of(ProtocolClaim.rest());
    }

    /**
     * The claim of a request {@link #claim} could not claim, made from the route JAX-RS matched it
     * to. With strict claiming off such a request is still served, by a route that dispatches it the
     * same way whatever its {@code Smithy-Protocol} header or content type says: the JSON, CBOR and
     * Query endpoints on {@code X-Amz-Target}, the rpcv2 path and the form, and every other route as
     * its REST operation. Claimed this way, every check that reads the claim sees the call that runs.
     */
    public ProtocolClaim routeClaim(Class<?> resourceClass, ClaimSignals signals,
                                    MultivaluedMap<String, String> pathParameters) {
        if (resourceClass == AwsJsonController.class) {
            return targetClaim(WireProtocol.AWS_JSON_1_0, signals.xAmzTarget());
        }
        if (resourceClass == AwsJson11Controller.class) {
            return targetClaim(WireProtocol.AWS_JSON_1_1, signals.xAmzTarget());
        }
        if (resourceClass == AwsJsonCborController.class) {
            ProtocolClaim rpcV2 = rpcV2RouteClaim(catalog, pathParameters);
            return rpcV2 != null ? rpcV2 : targetClaim(WireProtocol.AWS_CBOR_TARGET, signals.xAmzTarget());
        }
        if (resourceClass == AwsQueryController.class && "POST".equalsIgnoreCase(signals.method())) {
            return queryClaim(signals);
        }
        // The Query endpoint's GET route serves only the Query GET that claim() rewrites onto it;
        // reached directly it is no Query call, as claim() would say without the header.
        return ProtocolClaim.rest();
    }

    /**
     * The claim of a call routed to the Smithy RPC v2 endpoint, made from the service and operation
     * in the route's path parameters, which are what that endpoint dispatches. Null when the
     * parameters do not name both, as on the endpoint's {@code X-Amz-Target} route.
     */
    static ProtocolClaim rpcV2RouteClaim(ResolvedServiceCatalog catalog,
                                         MultivaluedMap<String, String> pathParameters) {
        String serviceId = pathParameters.getFirst(AwsJsonCborController.RPC_V2_SERVICE_PARAMETER);
        String operation = pathParameters.getFirst(AwsJsonCborController.RPC_V2_OPERATION_PARAMETER);
        if (serviceId == null || operation == null) {
            return null;
        }
        return rpcV2Claim(catalog, WireProtocol.RPCV2_CBOR, serviceId, operation);
    }

    private static ProtocolClaim rpcV2Claim(ResolvedServiceCatalog catalog, WireProtocol protocol,
                                            String serviceName, String operation) {
        ServiceDescriptor service = catalog.byCborSdkServiceId(serviceName).orElse(null);
        return new ProtocolClaim(protocol, service, operation, null);
    }

    private ProtocolClaim rpcV2Claim(WireProtocol protocol, Matcher rpcV2Path) {
        return rpcV2Claim(catalog, protocol, rpcV2Path.group(1), rpcV2Path.group(2));
    }

    private ProtocolClaim targetClaim(WireProtocol protocol, String target) {
        return catalog.matchTarget(target)
                .map(match -> new ProtocolClaim(protocol, match.descriptor(), match.action(), target))
                .orElseGet(() -> new ProtocolClaim(protocol, null, null, target));
    }

    private ProtocolClaim queryClaim(ClaimSignals signals) {
        ServiceDescriptor service = SigV4CredentialScope.serviceName(signals.authorization())
                .flatMap(catalog::byCredentialScope)
                .orElse(null);
        return new ProtocolClaim(WireProtocol.AWS_QUERY, service, null, null);
    }

    static boolean isRpcV2Path(String path) {
        return path != null && RPC_V2_PATH.matcher(path).matches();
    }

    private boolean isQueryGet(ClaimSignals signals) {
        String authorization = IamEnforcementFilter.requestAuthorization(
                signals.authorization(), signals.queryParameters());
        if (authorization != null) {
            return SigV4CredentialScope.serviceName(authorization).flatMap(catalog::byCredentialScope)
                    .filter(service -> service.supportsProtocol(ServiceProtocol.QUERY)).isPresent();
        }
        String action = AwsQueryServiceResolver.action(signals.queryParameters());
        return signals.xAmzTarget() == null
                && (signals.accept() == null || !signals.accept().contains(MediaType.TEXT_HTML))
                && action != null && "monitoring".equals(AwsQueryController.inferServiceFromAction(
                    action, signals.queryParameters().getFirst("Version")));
    }

    private static boolean isRootPath(String path) {
        return path == null || path.isEmpty() || "/".equals(path);
    }

    private static boolean isCbor(String mediaType) {
        return CONTENT_TYPE_CBOR.equals(mediaType) || CONTENT_TYPE_CBOR_1_1.equals(mediaType);
    }

    /**
     * Normalizes a Content-Type header to its lowercase base type, dropping
     * media-type parameters such as {@code ; charset=utf-8}. Works on the raw
     * header string because malformed values must not throw during claiming.
     */
    private static String baseMediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int paramsStart = contentType.indexOf(';');
        String base = paramsStart >= 0 ? contentType.substring(0, paramsStart) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }
}
