package io.floci.aws.core.common;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.UnaryOperator;

/** Checks and returns one DNS snapshot so the connection uses the addresses its caller validated. */
public final class ValidatingDnsResolver implements DnsResolver {

    @FunctionalInterface
    public interface AddressCheck {
        void validate(String host, InetAddress[] addresses) throws UnknownHostException;
    }

    private final DnsResolver delegate;
    private final AddressCheck check;
    private final UnaryOperator<String> canonicalHost;

    public ValidatingDnsResolver(DnsResolver delegate, AddressCheck check) {
        this(delegate, check, UnaryOperator.identity());
    }

    public ValidatingDnsResolver(DnsResolver delegate, AddressCheck check, UnaryOperator<String> canonicalHost) {
        this.delegate = delegate;
        this.check = check;
        this.canonicalHost = canonicalHost;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = delegate.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("Host has no addresses: " + canonicalHost.apply(host));
        }
        InetAddress[] snapshot = addresses.clone();
        check.validate(host, snapshot);
        return snapshot;
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return canonicalHost.apply(host);
    }
}
