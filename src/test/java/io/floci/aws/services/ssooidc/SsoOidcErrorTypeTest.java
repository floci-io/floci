package io.floci.aws.services.ssooidc;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every OAuth error code Floci answers with maps to the exception the SSO OIDC API models, which
 * the SDKs resolve from the x-amzn-ErrorType header.
 */
class SsoOidcErrorTypeTest {

    @ParameterizedTest
    @CsvSource({
            "access_denied, AccessDeniedException",
            "authorization_pending, AuthorizationPendingException",
            "expired_token, ExpiredTokenException",
            "invalid_client, InvalidClientException",
            "invalid_client_metadata, InvalidClientMetadataException",
            "invalid_grant, InvalidGrantException",
            "invalid_redirect_uri, InvalidRedirectUriException",
            "invalid_request, InvalidRequestException",
            "invalid_scope, InvalidScopeException",
            "server_error, InternalServerException",
            "slow_down, SlowDownException",
            "unauthorized_client, UnauthorizedClientException",
            "unsupported_grant_type, UnsupportedGrantTypeException",
    })
    void namesTheModeledException(String oauthError, String exception) {
        assertEquals(exception, SsoOidcController.exceptionName(oauthError));
    }
}
