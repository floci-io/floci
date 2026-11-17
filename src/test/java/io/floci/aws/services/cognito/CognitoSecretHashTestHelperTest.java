package io.floci.aws.services.cognito;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.floci.aws.services.cognito.CognitoSecretHashTestHelper.secretHash;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CognitoSecretHashTestHelperTest {

    // Fixed vectors calculated independently with Python's hmac, hashlib and base64 modules.
    @ParameterizedTest
    @CsvSource({
            "client123, secret456, Alice@example.com, UAF9CmSqTygxn4m0hhVjVVBPyztYjZBZxbhPZIwkrYU=",
            "client123, secret456, alice@example.com, VBQmj4yOlg2dnLjdFUcJTQKJxJp8nnjFCjgK0LPAsao=",
            "other-client, secret456, Alice@example.com, xmSh78fSroLosX7/3koymYFUYqP808ITCcp9MzgSOzQ=",
            "client123, other-secret, Alice@example.com, 8CiSDeuqhPw0+TSZpusFhWU6v+bSVtCrS+sLrcoNZZg=",
            "clïent, sëcret🔑, 用户@example.com, eth4htEdy7jhZnEVn3LX34SfM8WhSb82MKvn8LPiig0="
    })
    void secretHashMatchesKnownVectors(String clientId, String clientSecret, String username, String expected) {
        assertEquals(expected, secretHash(clientId, clientSecret, username));
    }
}
