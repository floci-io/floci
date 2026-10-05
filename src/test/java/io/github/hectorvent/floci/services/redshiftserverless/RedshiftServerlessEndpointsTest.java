package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessEndpointsTest {

    private final RedshiftService redshift = mock(RedshiftService.class);
    private final RedshiftServerlessEndpoints endpoints = new RedshiftServerlessEndpoints(redshift);

    @Test
    void allocateReservesAPortFromTheSharedPoolAndAdvertisesIt() {
        when(redshift.reserveProxyPort()).thenReturn(7101);
        when(redshift.advertisedEndpoint(7101)).thenReturn(new Endpoint("localhost", 7101));

        Endpoint allocated = endpoints.allocate();

        assertEquals("localhost", allocated.getAddress());
        assertEquals(7101, allocated.getPort());
    }

    @Test
    void restoreKeepsThePersistedPortAndRebuildsTheAddressFromCurrentConfiguration() {
        when(redshift.reserveProxyPort(7105)).thenReturn(true);
        when(redshift.advertisedEndpoint(7105)).thenReturn(new Endpoint("new-host", 7105));

        Endpoint restored = endpoints.restore(new Endpoint("old-host", 7105));

        assertEquals("new-host", restored.getAddress());
        assertEquals(7105, restored.getPort());
        verify(redshift, never()).reserveProxyPort();
    }

    @Test
    void restoreAllocatesAFreshEndpointWhenThePersistedPortIsTaken() {
        when(redshift.reserveProxyPort(7105)).thenReturn(false);
        when(redshift.reserveProxyPort()).thenReturn(7110);
        when(redshift.advertisedEndpoint(7110)).thenReturn(new Endpoint("localhost", 7110));

        assertEquals(7110, endpoints.restore(new Endpoint("localhost", 7105)).getPort());
    }

    @Test
    void restoreOfAWorkgroupWithNoEndpointAllocatesOne() {
        when(redshift.reserveProxyPort()).thenReturn(7111);
        when(redshift.advertisedEndpoint(7111)).thenReturn(new Endpoint("localhost", 7111));

        assertEquals(7111, endpoints.restore(null).getPort());
    }

    @Test
    void releaseReturnsThePortAndToleratesNull() {
        endpoints.release(new Endpoint("localhost", 7120));
        endpoints.release(null);

        verify(redshift).releaseProxyPort(7120);
    }
}
