package io.floci.aws.services.rds;

import io.floci.aws.core.common.dns.DnsAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An instance's endpoint name in its VPC resolves to the address its relay holds. */
class RdsDnsRecordSourceTest {

    private static final String NAME = "db1.abcdefghijkl.us-east-1.rds.amazonaws.com";

    private RdsEndpointAddresses endpointAddresses;
    private RdsDnsRecordSource source;

    @BeforeEach
    void setUp() {
        endpointAddresses = mock(RdsEndpointAddresses.class);
        when(endpointAddresses.enabled()).thenReturn(true);
        when(endpointAddresses.names(NAME)).thenReturn(true);
        when(endpointAddresses.addressOf(NAME)).thenReturn(Optional.of("10.0.1.20"));
        source = new RdsDnsRecordSource(endpointAddresses);
    }

    @Test
    void aServedEndpointResolvesToItsAddress() {
        DnsAnswer answer = source.resolveAuthoritative(NAME, 1).orElseThrow();

        assertEquals(List.of("10.0.1.20"), answer.addresses());
        assertEquals(List.of("10.0.1.20"), source.resolveAuthoritative(NAME + ".", 1).orElseThrow().addresses());
    }

    @Test
    void anEndpointHasNoIpv6Address() {
        DnsAnswer answer = source.resolveAuthoritative(NAME, 28).orElseThrow();

        assertTrue(answer.isEmpty());
        assertTrue(answer.nameExists());
    }

    @Test
    void aStoppedInstancesNameHasNoAddress() {
        when(endpointAddresses.addressOf(NAME)).thenReturn(Optional.empty());

        DnsAnswer answer = source.resolveAuthoritative(NAME, 1).orElseThrow();

        assertTrue(answer.isEmpty());
        assertTrue(answer.nameExists());
    }

    @Test
    void aNameNoInstanceIsServedUnderIsLeftToTheOtherSources() {
        assertFalse(source.resolveAuthoritative("other.abcdefghijkl.us-east-1.rds.amazonaws.com", 1).isPresent());
        assertFalse(source.resolveIpv4(NAME).isPresent());
    }

    @Test
    void aNameOutsideRdsIsNotLookedUp() {
        assertFalse(source.resolveAuthoritative("sqs.us-east-1.amazonaws.com", 1).isPresent());

        verify(endpointAddresses, never()).names(anyString());
    }

    @Test
    void nothingIsLookedUpWithEndpointsInTheirVpcOff() {
        when(endpointAddresses.enabled()).thenReturn(false);

        assertFalse(source.resolveAuthoritative(NAME, 1).isPresent());

        verify(endpointAddresses, never()).names(anyString());
    }
}
