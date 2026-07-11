package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Claims each request's wire protocol via {@link ProtocolClaimer} and exposes
 * the result as a request property for downstream filters and controllers.
 * <p>
 * Runs pre-matching at priority 6000 so every path-rewriting pre-matching
 * filter (SQS queue URLs, S3 virtual hosts, Lambda URLs — default priority
 * 5000) and {@link AwsCborContentTypeFilter} (3000) has already produced the
 * final path and stashed the original content type.
 */
@Provider
@PreMatching
@Priority(6000)
public class AwsProtocolClaimFilter implements ContainerRequestFilter {

    public static final String CLAIM_PROPERTY = "floci.protocol.claim";

    private static final Logger LOG = Logger.getLogger(AwsProtocolClaimFilter.class);

    private final ProtocolClaimer claimer;
    // Lazily resolved: JAX-RS providers are instantiated before runtime config
    // mappings exist (same pattern as GlobalCorsFilter).
    private final jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    public AwsProtocolClaimFilter(ProtocolClaimer claimer, jakarta.inject.Provider<EmulatorConfig> configProvider) {
        this.claimer = claimer;
        this.configProvider = configProvider;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        ClaimSignals signals = ClaimSignals.from(ctx);
        Optional<ProtocolClaim> claim = claimer.claim(signals);

        if (claim.isEmpty()) {
            LOG.warnv("Unclaimable RPC-signaled request: {0} {1} Content-Type={2} Smithy-Protocol={3}",
                    signals.method(), signals.path(), signals.contentType(), signals.smithyProtocol());
            if (configProvider.get().protocols().strictClaiming()) {
                ctx.abortWith(unknownOperationResponse(404,
                        "Unable to determine the wire protocol for this request"));
            }
            return;
        }

        ProtocolClaim resolved = claim.get();
        ctx.setProperty(CLAIM_PROPERTY, resolved);
        if (resolved.protocol() == WireProtocol.RPCV2_JSON) {
            LOG.warnv("Received rpc-v2-json request for service {0} operation {1} — protocol not implemented",
                    resolved.service() != null ? resolved.service().externalKey() : "unknown",
                    resolved.operation());
            if (configProvider.get().protocols().strictClaiming()) {
                ctx.abortWith(unknownOperationResponse(400,
                        "The rpc-v2-json protocol is not supported"));
            }
        } else if (resolved.protocol() != WireProtocol.REST) {
            LOG.debugv("Protocol claim: {0} service={1} operation={2}",
                    resolved.protocol(),
                    resolved.service() != null ? resolved.service().externalKey() : null,
                    resolved.operation());
        }
    }

    private Response unknownOperationResponse(int status, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header("x-amzn-query-error", "UnknownOperationException;Sender")
                .entity(new AwsErrorResponse("UnknownOperationException", message))
                .build();
    }
}
