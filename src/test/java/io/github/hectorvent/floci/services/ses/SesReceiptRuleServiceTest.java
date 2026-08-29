package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.ReceiptRuleSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit test for the extracted receipt-rule domain. The payoff of the split: the
 * service is constructed with just its own store and a clock — no 14-argument SesService needed.
 */
class SesReceiptRuleServiceTest {

    private static final String REGION = "us-east-1";
    private SesReceiptRuleService service;

    @BeforeEach
    void setUp() {
        service = new SesReceiptRuleService(new InMemoryStorage<>(), Clock.systemUTC());
    }

    @Test
    void create_thenDescribe_roundTrips() {
        service.createReceiptRuleSet("rules-a", REGION);
        assertEquals("rules-a", service.describeReceiptRuleSet("rules-a", REGION).getName());
    }

    @Test
    void create_duplicate_throwsAlreadyExists() {
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRuleSet("rules-a", REGION));
        assertEquals("AlreadyExists", e.getErrorCode());
    }

    @Test
    void describe_unknown_throwsRuleSetDoesNotExist() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.describeReceiptRuleSet("ghost", REGION));
        assertEquals("RuleSetDoesNotExist", e.getErrorCode());
    }

    @Test
    void setActive_thenDescribeActive_andDeleteActiveRejected() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.setActiveReceiptRuleSet("rules-a", REGION);

        ReceiptRuleSet active = service.describeActiveReceiptRuleSet(REGION);
        assertNotNull(active);
        assertEquals("rules-a", active.getName());

        AwsException e = assertThrows(AwsException.class,
                () -> service.deleteReceiptRuleSet("rules-a", REGION));
        assertEquals("CannotDelete", e.getErrorCode());
    }

    @Test
    void invalidName_throwsValidationError() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRuleSet("bad name!", REGION));
        assertEquals("ValidationError", e.getErrorCode());
    }
}
