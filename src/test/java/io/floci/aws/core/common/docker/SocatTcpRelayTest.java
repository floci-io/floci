package io.floci.aws.core.common.docker;

import org.junit.jupiter.api.Test;

import static io.floci.aws.core.common.docker.SocatTcpRelay.connect;
import static io.floci.aws.core.common.docker.SocatTcpRelay.connectIpv6;
import static io.floci.aws.core.common.docker.SocatTcpRelay.listen;
import static io.floci.aws.core.common.docker.SocatTcpRelay.listenDualStack;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The socat addresses a relay container is started with; nothing here opens a socket. */
class SocatTcpRelayTest {

    @Test
    void aListenerForksAChildPerConnectionWithNoDelayAndTheDataPlaneBacklog() {
        assertEquals("TCP-LISTEN:5432,fork,reuseaddr,nodelay,backlog=4096", listen(5432));
    }

    @Test
    void aBoundListenerNamesItsAddressBeforeItsOptions() {
        assertEquals("TCP-LISTEN:80,bind=169.254.170.2,fork,reuseaddr,nodelay,backlog=4096",
                listen(80, "169.254.170.2"));
    }

    @Test
    void aDualStackListenerTakesIpv4ClientsOnItsIpv6Socket() {
        assertEquals("TCP6-LISTEN:443,ipv6only=0,fork,reuseaddr,nodelay,backlog=4096", listenDualStack(443));
    }

    @Test
    void aTargetIsDialledWithNoDelay() {
        assertEquals("TCP:host.docker.internal:7001,nodelay", connect("host.docker.internal", 7001));
        assertEquals("TCP6:[fd00:d0c::5]:443,nodelay", connectIpv6("fd00:d0c::5", 443));
    }
}
