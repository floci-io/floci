package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted sent-email record domain: the send path's output sink plus the
 * per-region count and the inspection list/clear. The service is constructed with just its own store
 * — no 14-argument SesService needed.
 */
class SesSentEmailServiceTest {

    private static final String REGION = "us-east-1";
    private SesSentEmailService service;

    @BeforeEach
    void setUp() {
        service = new SesSentEmailService(new InMemoryStorage<>());
    }

    private static SentEmail email(String messageId, String region) {
        SentEmail e = new SentEmail();
        e.setMessageId(messageId);
        e.setRegion(region);
        e.setSource("sender@example.com");
        return e;
    }

    @Test
    void record_thenListAll() {
        service.record(REGION, "m1", email("m1", REGION));
        service.record(REGION, "m2", email("m2", REGION));

        List<SentEmail> all = service.listAll();
        assertEquals(2, all.size());
    }

    @Test
    void countInRegion_isPerRegion() {
        service.record(REGION, "m1", email("m1", REGION));
        service.record(REGION, "m2", email("m2", REGION));
        service.record("eu-west-1", "m3", email("m3", "eu-west-1"));

        assertEquals(2, service.countInRegion(REGION));
        assertEquals(1, service.countInRegion("eu-west-1"));
        assertEquals(0, service.countInRegion("ap-northeast-1"));
    }

    @Test
    void record_sameMessageId_isOverwrittenNotDuplicated() {
        service.record(REGION, "m1", email("m1", REGION));
        service.record(REGION, "m1", email("m1", REGION));
        assertEquals(1, service.countInRegion(REGION));
    }

    @Test
    void clear_removesEverything() {
        service.record(REGION, "m1", email("m1", REGION));
        service.record("eu-west-1", "m2", email("m2", "eu-west-1"));

        service.clear();
        assertTrue(service.listAll().isEmpty());
        assertEquals(0, service.countInRegion(REGION));
    }
}
