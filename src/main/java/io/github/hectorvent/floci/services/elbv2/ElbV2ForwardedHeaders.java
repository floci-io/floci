package io.github.hectorvent.floci.services.elbv2;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The {@code X-Forwarded-*} headers an Application Load Balancer sends a target, taken from the client's
 * connection to the listener rather than from anything the client sent.
 *
 * <p>{@code routing.http.xff_header_processing.mode} decides {@code X-Forwarded-For}: {@code append} (the
 * default) adds the client address to whatever the client sent, {@code preserve} leaves the header untouched
 * and {@code remove} drops it. {@code routing.http.xff_client_port.enabled} only affects {@code append}.
 */
record ElbV2ForwardedHeaders(SocketAddress client, boolean secure, int listenerPort,
                             String xffMode, boolean xffClientPort) {

    static final String XFF_MODE_ATTRIBUTE = "routing.http.xff_header_processing.mode";
    static final String XFF_CLIENT_PORT_ATTRIBUTE = "routing.http.xff_client_port.enabled";
    static final Set<String> XFF_MODES = Set.of("append", "preserve", "remove");

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String FORWARDED_PROTO = "X-Forwarded-Proto";
    private static final String FORWARDED_PORT = "X-Forwarded-Port";

    static ElbV2ForwardedHeaders of(HttpServerRequest req, String xffMode, boolean xffClientPort) {
        return new ElbV2ForwardedHeaders(req.remoteAddress(), req.isSSL(), req.localAddress().port(),
                xffMode, xffClientPort);
    }

    /** Sets the headers on {@code outbound}, which may already hold a copy of {@code inbound}. */
    void applyTo(MultiMap inbound, MultiMap outbound) {
        List<String> received = inbound.getAll(FORWARDED_FOR);
        outbound.remove(FORWARDED_FOR);
        switch (xffMode != null ? xffMode : "append") {
            case "preserve" -> outbound.set(FORWARDED_FOR, received);
            case "remove" -> { }
            default -> {
                String appended = appended(received);
                if (!appended.isEmpty()) {
                    outbound.set(FORWARDED_FOR, appended);
                }
            }
        }
        outbound.set(FORWARDED_PROTO, secure ? "https" : "http");
        outbound.set(FORWARDED_PORT, String.valueOf(listenerPort));
    }

    private String appended(List<String> received) {
        List<String> entries = new ArrayList<>();
        for (String value : received) {
            if (!value.isBlank()) {
                entries.add(value);
            }
        }
        if (client != null) {
            entries.add(clientEntry());
        }
        return String.join(", ", entries);
    }

    private String clientEntry() {
        String address = client.host();
        if (!xffClientPort) {
            return address;
        }
        String host = address.contains(":") ? "[" + address + "]" : address;
        return host + ":" + client.port();
    }
}
