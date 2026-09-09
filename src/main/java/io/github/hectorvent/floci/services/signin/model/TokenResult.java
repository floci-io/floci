package io.github.hectorvent.floci.services.signin.model;

import io.github.hectorvent.floci.services.iam.model.SessionCreds;

public record TokenResult(SessionCreds accessToken, int expiresIn,
                          String refreshToken, String idToken) {
}
