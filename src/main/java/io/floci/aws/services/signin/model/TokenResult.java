package io.floci.aws.services.signin.model;

import io.floci.aws.services.iam.model.SessionCreds;

public record TokenResult(SessionCreds accessToken, int expiresIn,
                          String refreshToken, String idToken) {
}
