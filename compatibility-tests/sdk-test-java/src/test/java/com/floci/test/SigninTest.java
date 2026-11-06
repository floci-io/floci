package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.signin.SigninClient;
import software.amazon.awssdk.services.signin.model.OAuth2ErrorCode;
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
                        assertThat(exception.error()).isEqualTo(OAuth2ErrorCode.INVALID_REQUEST);
                    });
        }
    }
}
