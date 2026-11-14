package io.floci.aws.core.common;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ValidatingDnsResolverTest {

    private final DnsResolver delegate = mock(DnsResolver.class);

    @Test
    void validatesAndReturnsTheSameSnapshotWithoutCanonicalDnsLookup() throws Exception {
        InetAddress publicIp = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        InetAddress[] answer = {publicIp};
        when(delegate.resolve("ORIGIN")).thenReturn(answer);
        AtomicReference<InetAddress[]> checked = new AtomicReference<>();
        ValidatingDnsResolver resolver = new ValidatingDnsResolver(delegate, (host, addresses) -> checked.set(addresses),
                host -> host.toLowerCase(Locale.ROOT));
        InetAddress[] resolved = resolver.resolve("ORIGIN");
        answer[0] = InetAddress.getLoopbackAddress();
        assertSame(checked.get(), resolved);
        assertEquals(publicIp, resolved[0]);
        assertEquals("origin", resolver.resolveCanonicalHostname("ORIGIN"));
        verify(delegate).resolve("ORIGIN");
        verify(delegate, never()).resolveCanonicalHostname("ORIGIN");
    }

    @Test
    void absentDnsAnswersFailBeforeThePolicyRuns() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        ValidatingDnsResolver resolver = new ValidatingDnsResolver(delegate, (host, addresses) -> checks.incrementAndGet());
        for (InetAddress[] answer : new InetAddress[][]{null, new InetAddress[0]}) {
            when(delegate.resolve("missing")).thenReturn(answer);
            assertThrows(UnknownHostException.class, () -> resolver.resolve("missing"));
        }
        assertEquals(0, checks.get());
    }

    @Test
    void callerSpecificRejectionIsPreserved() throws Exception {
        when(delegate.resolve("blocked")).thenReturn(new InetAddress[]{InetAddress.getLoopbackAddress()});
        UnknownHostException blocked = new UnknownHostException("service-specific refusal");
        ValidatingDnsResolver resolver = new ValidatingDnsResolver(delegate, (host, addresses) -> { throw blocked; });
        assertSame(blocked, assertThrows(UnknownHostException.class, () -> resolver.resolve("blocked")));
    }
}
