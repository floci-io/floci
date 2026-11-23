package io.floci.aws.core.common;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AwsJson11ControllerAuthorizationTest {

    @Test
    void nonBlankHeaderWinsWithoutReadingQueryParameters() {
        HttpHeaders headers = mock(HttpHeaders.class);
        UriInfo uriInfo = mock(UriInfo.class);
        String authorization = "AWS4-HMAC-SHA256 Credential=header/20261121/us-east-1/ssm/aws4_request";
        when(headers.getHeaderString("Authorization")).thenReturn(authorization);

        assertEquals(authorization, AwsJson11Controller.requestAuthorization(headers, uriInfo));
        verifyNoInteractions(uriInfo);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void absentOrBlankHeaderFallsBackToPresignedQuery(String authorization) {
        HttpHeaders headers = mock(HttpHeaders.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        String credential = "query/20261121/us-east-1/ssm/aws4_request";
        query.putSingle("X-Amz-Credential", credential);
        when(headers.getHeaderString("Authorization")).thenReturn(authorization);
        when(uriInfo.getQueryParameters()).thenReturn(query);

        assertEquals("AWS4-HMAC-SHA256 Credential=" + credential,
                AwsJson11Controller.requestAuthorization(headers, uriInfo));
    }

    @Test
    void absentHeaderAndCredentialRemainUnsigned() {
        HttpHeaders headers = mock(HttpHeaders.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());

        assertNull(AwsJson11Controller.requestAuthorization(headers, uriInfo));
    }
}
