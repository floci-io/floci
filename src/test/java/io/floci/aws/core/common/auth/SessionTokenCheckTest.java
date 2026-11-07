package io.floci.aws.core.common.auth;

import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionTokenCheckTest {

    @Test
    void aLongTermKeyComesWithoutAToken() {
        IamService iam = mock(IamService.class);
        assertEquals(SessionTokenCheck.VALID, SessionTokenCheck.of(iam, "AKIAEXAMPLE", null));
        assertEquals(SessionTokenCheck.MISSING, SessionTokenCheck.of(iam, "AKIAEXAMPLE", ""));
        assertEquals(SessionTokenCheck.INVALID, SessionTokenCheck.of(iam, "AKIAEXAMPLE", "bogus"));
    }

    @Test
    void aTemporaryKeyNeedsTheTokenIssuedWithIt() {
        IamService iam = mock(IamService.class);
        when(iam.findSessionToken("ASIAEXAMPLE")).thenReturn(Optional.of("issued"));
        assertEquals(SessionTokenCheck.MISSING, SessionTokenCheck.of(iam, "ASIAEXAMPLE", null));
        assertEquals(SessionTokenCheck.MISSING, SessionTokenCheck.of(iam, "ASIAEXAMPLE", ""));
        assertEquals(SessionTokenCheck.VALID, SessionTokenCheck.of(iam, "ASIAEXAMPLE", "issued"));
        assertEquals(SessionTokenCheck.INVALID, SessionTokenCheck.of(iam, "ASIAEXAMPLE", "bogus"));
    }

    @Test
    void aSessionWithNoRecordedTokenTakesAnyToken() {
        IamService iam = mock(IamService.class);
        when(iam.findSessionToken("ASIAOLD")).thenReturn(Optional.empty());
        assertEquals(SessionTokenCheck.VALID, SessionTokenCheck.of(iam, "ASIAOLD", "anything"));
        assertEquals(SessionTokenCheck.MISSING, SessionTokenCheck.of(iam, "ASIAOLD", null));
    }
}
