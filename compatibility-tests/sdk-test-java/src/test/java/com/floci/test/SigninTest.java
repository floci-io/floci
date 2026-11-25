package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.signin.SigninClient;
import software.amazon.awssdk.services.signin.model.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AWS Sign-In")
class SigninTest {

    @Test
    void tokenValidationErrorsUseTheModeledAwsResponse() {
        try (SigninClient signin = TestFixtures.signinClient()) {
            assertThatThrownBy(() -> signin.createOAuth2Token(request -> request
                    .tokenInput(input -> input
                            .clientId("arn:aws:signin:::devtools/same-device")
                            .grantType("authorization_code")
                            .code("invalid-authorization-code")
                            .redirectUri("http://127.0.0.1:4567/oauth/callback")
                            .codeVerifier("a".repeat(43)))))
                    .isInstanceOfSatisfying(ValidationException.class, exception -> {
                        assertThat(exception.statusCode()).isEqualTo(400);
                        assertThat(exception.awsErrorDetails().errorCode()).isEqualTo("ValidationException");
                        // AWS sends "INVALID_REQUEST" (probed in us-west-2, 2026-11-26), while SDK 2.59.7
                        // maps OAuth2ErrorCode.INVALID_REQUEST to "invalid_request", so error() reads it as
                        // UNKNOWN_TO_SDK_VERSION against AWS too. Assert the wire value.
                        assertThat(exception.errorAsString()).isEqualTo("INVALID_REQUEST");
                    });
        }
    }
}
