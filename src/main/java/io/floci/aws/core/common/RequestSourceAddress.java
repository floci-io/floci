package io.floci.aws.core.common;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;

/**
 * The address a request was made from, as CloudTrail's {@code sourceIPAddress} names it: the address of the
 * connection the request arrived on. An {@code X-Forwarded-For} header is not read on its own, since any client can
 * send one. Behind a proxy that Quarkus's own proxy settings trust, Quarkus gives the request the address that proxy
 * forwards as its remote address.
 */
public final class RequestSourceAddress {

    private RequestSourceAddress() {
    }

    /** The request's remote host, or null when the connection names none. */
    public static String of(HttpServerRequest request) {
        SocketAddress remote = request.remoteAddress();
        return remote != null ? remote.host() : null;
    }
}
