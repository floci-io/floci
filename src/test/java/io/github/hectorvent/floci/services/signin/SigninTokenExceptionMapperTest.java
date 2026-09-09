package io.github.hectorvent.floci.services.signin;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class SigninTokenExceptionMapperTest {

    private final SigninTokenExceptionMapper mapper = new SigninTokenExceptionMapper();

    @Test
    void modeledValidationUsesAwsErrorHeaderAndBody() {
        Response response = mapper.toResponse(SigninTokenException.validation());

        assertEquals(400, response.getStatus());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertEquals("ValidationException", response.getHeaderString("X-Amzn-ErrorType"));
        assertEquals(Map.of(
                "error", "INVALID_REQUEST",
                "message", SigninTokenException.INVALID_REQUEST_MESSAGE), response.getEntity());
    }

    @Test
    void modeledExpiryUsesAccessDeniedStatusAndErrorCode() {
        Response response = mapper.toResponse(SigninTokenException.refreshTokenExpired());

        assertEquals(401, response.getStatus());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertEquals("AccessDeniedException", response.getHeaderString("X-Amzn-ErrorType"));
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getEntity();
        assertEquals("TOKEN_EXPIRED", body.get("error"));
        assertEquals("The refresh token is invalid or expired", body.get("message"));
        assertFalse(body.containsKey("error_description"));
    }

    @Test
    void oauthErrorUsesDescriptionWithoutAwsErrorHeader() {
        Response response = mapper.toResponse(SigninTokenException.unsupportedGrant());

        assertEquals(400, response.getStatus());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertNull(response.getHeaderString("X-Amzn-ErrorType"));
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getEntity();
        assertEquals("unsupported_grant_type", body.get("error"));
        assertEquals(SigninTokenException.UNSUPPORTED_GRANT_MESSAGE, body.get("error_description"));
        assertFalse(body.containsKey("message"));
    }
}
