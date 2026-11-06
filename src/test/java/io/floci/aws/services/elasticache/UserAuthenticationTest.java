package io.floci.aws.services.elasticache;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.elasticache.model.AuthMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserAuthenticationTest {

    @Test
    void nothingGivenResolvesToNull() {
        assertNull(UserAuthentication.resolve(null, List.of(), false));
    }

    @Test
    void passwordsWithoutTypeMeanPasswordAuthentication() {
        UserAuthentication auth = UserAuthentication.resolve(null, List.of("p"), false);
        assertEquals(AuthMode.PASSWORD, auth.mode());
        assertEquals(List.of("p"), auth.passwords());
    }

    @Test
    void noPasswordRequiredWithoutTypeMeansNoAuth() {
        assertEquals(AuthMode.NO_AUTH, UserAuthentication.resolve(null, List.of(), true).mode());
    }

    @Test
    void typeIsMatchedCaseInsensitively() {
        assertEquals(AuthMode.IAM, UserAuthentication.resolve("IAM", List.of(), false).mode());
        assertEquals(AuthMode.NO_AUTH, UserAuthentication.resolve("No-Password-Required", List.of(), false).mode());
    }

    @Test
    void passwordTypeWithoutPasswordsIsRejected() {
        AwsException e = assertThrows(AwsException.class,
                () -> UserAuthentication.resolve("password", List.of(), false));
        assertEquals("InvalidParameterValue", e.getErrorCode());
    }

    @Test
    void conflictingInputsAreRejected() {
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> UserAuthentication.resolve(null, List.of("p"), true)).getErrorCode());
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> UserAuthentication.resolve("password", List.of("p"), true)).getErrorCode());
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> UserAuthentication.resolve("iam", List.of("p"), false)).getErrorCode());
    }

    @Test
    void unknownTypeIsRejected() {
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> UserAuthentication.resolve("kerberos", List.of(), false)).getErrorCode());
    }
}
