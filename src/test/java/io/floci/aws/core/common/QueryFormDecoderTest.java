package io.floci.aws.core.common;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryFormDecoderTest {

    @ParameterizedTest
    @CsvSource({"ec2,404", "sts,404", "'',404"})
    void malformedEncodingUsesTheClaimWithoutReadingTheFormAgain(String service, int status) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        ServiceDescriptor descriptor = service.isEmpty() ? null : new ServiceDescriptor(service, service,
                true, true, null, null, 0L, null, ServiceProtocol.QUERY, Set.of(ServiceProtocol.QUERY),
                Set.of(), Set.of(service), Set.of(), Set.of(), Set.of());
        when(ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY))
                .thenReturn(new ProtocolClaim(WireProtocol.AWS_QUERY, descriptor, null, null));
        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> QueryFormDecoder.decode("%ZZ", StandardCharsets.UTF_8, ctx));
        assertEquals(status, error.getResponse().getStatus());
        assertEquals("application/xml", error.getResponse().getMediaType().toString());
        verify(ctx, never()).getEntityStream();
    }
}
