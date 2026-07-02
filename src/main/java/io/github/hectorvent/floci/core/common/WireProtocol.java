package io.github.hectorvent.floci.core.common;

/**
 * Concrete AWS wire protocols, declared in the Smithy wire-protocol-selection
 * precision order so claiming can iterate {@link #values()} highest-first.
 * Maps down to the coarser {@link ServiceProtocol} used by service descriptors,
 * which does not distinguish JSON versions or rpcv2 variants.
 *
 * @see <a href="https://smithy.io/2.0/guides/wire-protocol-selection.html">Smithy wire protocol selection</a>
 */
public enum WireProtocol {

    RPCV2_CBOR(ServiceProtocol.CBOR),
    RPCV2_JSON(null),
    AWS_JSON_1_0(ServiceProtocol.JSON),
    AWS_JSON_1_1(ServiceProtocol.JSON),
    AWS_QUERY(ServiceProtocol.QUERY),
    /**
     * Floci extension outside the Smithy precedence list: CBOR body with
     * X-Amz-Target routing at the root path, as sent by e.g. the Java SDK for
     * Kinesis (application/x-amz-cbor-1.1) before rpcv2 path-based routing.
     */
    AWS_CBOR_TARGET(ServiceProtocol.CBOR),
    /** restJson1/restXml traffic, delegated to JAX-RS path matching. */
    REST(null);

    private final ServiceProtocol legacy;

    WireProtocol(ServiceProtocol legacy) {
        this.legacy = legacy;
    }

    /** The coarse descriptor-level protocol, or null when none applies. */
    public ServiceProtocol legacy() {
        return legacy;
    }
}
