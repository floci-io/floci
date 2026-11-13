package io.floci.aws.core.common;

import io.floci.aws.services.iam.IamService;
import io.floci.aws.testutil.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

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

        assertEquals(List.of("A request in account 123456789012 was signed with an IAM user's access key "
                + "(AKIAUSERKEY), but IAM policy enforcement is off, so Floci allowed it without evaluating "
                + "the policies that apply to it. Set FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true to evaluate "
                + "them as AWS does. This is logged once."), warnings);
    }

    @Test
    void aSessionFlociIssuedIsNamedAsOne() {
        when(iamService.isKnownAccessKey("ASIAROLESESSION")).thenReturn(true);

        List<String> warnings = warnings(() -> assertTrue(notice.observe("ASIAROLESESSION", ACCOUNT)));

        assertEquals(List.of("A request in account 123456789012 was signed with a session Floci issued "
                + "(ASIAROLESESSION), but IAM policy enforcement is off, so Floci allowed it without evaluating "
                + "the policies that apply to it. Set FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true to evaluate "
                + "them as AWS does. This is logged once."), warnings);
    }

    @Test
    void theAccountRootsKeysNeverWarnAndAreNotLookedUp() {
        List<String> warnings = warnings(() -> {
            assertFalse(notice.observe("test", "000000000000"));
            assertFalse(notice.observe(ACCOUNT, ACCOUNT));
            assertFalse(notice.observe(null, ACCOUNT));
            assertFalse(notice.observe("", ACCOUNT));
        });

        assertEquals(List.of(), warnings);
        verifyNoInteractions(iamService);
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

    @Test
    void theKeysItRemembersAreBounded() {
        for (int i = 0; i < UnenforcedIamCredentialNotice.MAX_CHECKED_KEYS; i++) {
            notice.observe("AKIAUNKNOWN" + i, ACCOUNT);
        }

        notice.observe("AKIAONEMORE", ACCOUNT);
        notice.observe("AKIAONEMORE", ACCOUNT);

        verify(iamService, times(2)).isKnownAccessKey("AKIAONEMORE");
    }

    /** The warnings the notice logs while {@code action} runs, formatted as written. */
    private static List<String> warnings(Runnable action) {
        List<LogRecord> records = LogCapture.capture(UnenforcedIamCredentialNotice.class, action);
        return records.stream()
                .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
                .map(record -> record.getParameters() == null || record.getParameters().length == 0
                        ? record.getMessage()
                        : MessageFormat.format(record.getMessage(), record.getParameters()))
                .toList();
    }
}
