package io.github.hectorvent.floci.core.common;

/**
 * The result of claiming an inbound request's wire protocol from its
 * non-payload signals.
 *
 * @param protocol  the claimed wire protocol
 * @param service   the resolved service descriptor, or null when the claim
 *                  signals identify a protocol but not a known service
 * @param operation the operation name from the rpcv2 path or the X-Amz-Target
 *                  suffix, or null when not derivable from the claim signals
 * @param target    the raw X-Amz-Target header value, or null when absent
 */
public record ProtocolClaim(WireProtocol protocol, ServiceDescriptor service, String operation, String target) {

    static ProtocolClaim rest() {
        return new ProtocolClaim(WireProtocol.REST, null, null, null);
    }
}
