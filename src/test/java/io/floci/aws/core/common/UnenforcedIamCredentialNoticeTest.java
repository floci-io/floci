package io.floci.aws.core.common;

import io.floci.aws.services.iam.IamService;
import io.floci.aws.testutil.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UnenforcedIamCredentialNoticeTest {

    private static final String ACCOUNT = "123456789012";

    private IamService iamService;
    private UnenforcedIamCredentialNotice notice;

    @BeforeEach
    void setUp() {
        iamService = mock(IamService.class);
        notice = new UnenforcedIamCredentialNotice(iamService);
    }

    @Test
    void anIamUsersAccessKeyWarnsOnceNamingTheFlag() {
        when(iamService.isKnownAccessKey("AKIAUSERKEY")).thenReturn(true);
        when(iamService.isKnownAccessKey("AKIAOTHERUSER")).thenReturn(true);

        List<String> warnings = warnings(() -> {
            assertTrue(notice.observe("AKIAUSERKEY", ACCOUNT));
            assertFalse(notice.observe("AKIAUSERKEY", ACCOUNT));
            assertFalse(notice.observe("AKIAOTHERUSER", ACCOUNT));
        });

        assertEquals(List.of(expectedWarning(ACCOUNT, "an IAM user's access key", "AKIAUSERKEY")), warnings);
    }

    @Test
    void aSessionFlociIssuedIsNamedAsOne() {
        when(iamService.isKnownAccessKey("ASIAROLESESSION")).thenReturn(true);

        List<String> warnings = warnings(() -> assertTrue(notice.observe("ASIAROLESESSION", ACCOUNT)));

        assertEquals(List.of(expectedWarning(ACCOUNT, "a session Floci issued", "ASIAROLESESSION")), warnings);
    }

    @Test
    void theAccountRootsKeysNeverWarnAndAreNotLookedUp() {
        List<String> warnings = warnings(() -> {
            assertFalse(notice.observe("test", "000000000000"));
            assertFalse(notice.observe(ACCOUNT, ACCOUNT));
            assertFalse(notice.observe("210987654321", ACCOUNT));
            assertFalse(notice.observe(null, ACCOUNT));
            assertFalse(notice.observe("", ACCOUNT));
        });

        assertEquals(List.of(), warnings);
        verifyNoInteractions(iamService);
    }

    @Test
    void aSessionTheRootMintedNeverWarnsAndIsLookedUpOnce() {
        when(iamService.isKnownAccessKey("ASIAROOTSESSION")).thenReturn(true);
        when(iamService.isRootIssuedSessionToken("ASIAROOTSESSION")).thenReturn(true);

        List<String> warnings = warnings(() -> {
            assertFalse(notice.observe("ASIAROOTSESSION", ACCOUNT));
            assertFalse(notice.observe("ASIAROOTSESSION", ACCOUNT));
        });

        assertEquals(List.of(), warnings);
        verify(iamService, times(1)).isKnownAccessKey("ASIAROOTSESSION");
        verify(iamService, times(1)).isRootIssuedSessionToken("ASIAROOTSESSION");
    }

    @Test
    void aUrlFlociPresignedForItsOwnUseNeverWarnsAndIsLookedUpOnce() {
        when(iamService.isKnownAccessKey("ASIAPRESIGNED")).thenReturn(true);
        when(iamService.presignedScope("ASIAPRESIGNED")).thenReturn(Optional.of(
                new IamService.PresignedScope("lambda:GetFunction", "arn:aws:lambda:us-east-1:123456789012:function:f")));

        List<String> warnings = warnings(() -> {
            assertFalse(notice.observe("ASIAPRESIGNED", ACCOUNT));
            assertFalse(notice.observe("ASIAPRESIGNED", ACCOUNT));
        });

        assertEquals(List.of(), warnings);
        verify(iamService, times(1)).presignedScope("ASIAPRESIGNED");
    }

    @Test
    void theSeededDeployerKeyNeverWarns() {
        when(iamService.isKnownAccessKey("floci")).thenReturn(true);
        when(iamService.isSeededDeployerAccessKey("floci")).thenReturn(true);

        assertEquals(List.of(), warnings(() -> assertFalse(notice.observe("floci", ACCOUNT))));
    }

    @Test
    void aKeyFlociDidNotIssueIsLookedUpOnce() {
        List<String> warnings = warnings(() -> {
            assertFalse(notice.observe("AKIAIOSFODNN7EXAMPLE", ACCOUNT));
            assertFalse(notice.observe("AKIAIOSFODNN7EXAMPLE", ACCOUNT));
        });

        assertEquals(List.of(), warnings);
        verify(iamService, times(1)).isKnownAccessKey("AKIAIOSFODNN7EXAMPLE");
    }

    /**
     * It remembers the keys it saw most recently, up to its bound: one seen once the bound is reached is
     * looked up once all the same, and takes the place of the key seen longest ago.
     */
    @Test
    void itRemembersTheKeysSeenMostRecentlyUpToItsBound() {
        int bound = UnenforcedIamCredentialNotice.MAX_CHECKED_KEYS;
        for (int i = 0; i < bound; i++) {
            notice.observe("AKIAUNKNOWN" + i, ACCOUNT);
        }

        notice.observe("AKIAONEMORE", ACCOUNT);
        notice.observe("AKIAONEMORE", ACCOUNT);
        notice.observe("AKIAUNKNOWN" + (bound - 1), ACCOUNT);
        notice.observe("AKIAUNKNOWN0", ACCOUNT);

        verify(iamService, times(1)).isKnownAccessKey("AKIAONEMORE");
        verify(iamService, times(1)).isKnownAccessKey("AKIAUNKNOWN" + (bound - 1));
        verify(iamService, times(2)).isKnownAccessKey("AKIAUNKNOWN0");
        assertEquals(bound, notice.rememberedKeys());
    }

    private static List<String> warnings(Runnable action) {
        return LogCapture.warnings(UnenforcedIamCredentialNotice.class, action);
    }

    /** The warning for a request in {@code account} signed with {@code credential}, whose key is {@code key}. */
    static String expectedWarning(String account, String credential, String key) {
        return "A request in account " + account + " was signed with " + credential + " (" + key + "), but IAM "
                + "policy enforcement is off, so Floci allowed it without evaluating the policies that apply to it. "
                + "Set FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true to evaluate them as AWS does. This is logged once.";
    }
}
