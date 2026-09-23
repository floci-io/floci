package io.github.hectorvent.floci.services.textract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AiMockConfigLoader;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class TextractServiceTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void repeatedGetReturnsTheSameStableResult() {
        TextractService service = serviceAt(START);
        String jobId = startJob(service, "TEXT_DETECTION");

        ObjectNode first = (ObjectNode) service.getDocumentTextDetection(jobId).getEntity();
        ObjectNode second = (ObjectNode) service.getDocumentTextDetection(jobId).getEntity();

        assertEquals(first, second);
        assertEquals("SUCCEEDED", second.get("JobStatus").asText());
    }

    @Test
    void getBeforeExpiryStillReturnsTheResult() {
        MutableClock clock = new MutableClock(START);
        TextractService service = new TextractService(new ObjectMapper(), mock(AiMockConfigLoader.class), clock);
        String jobId = startJob(service, "DOCUMENT_ANALYSIS");

        clock.advance(TextractService.RESULT_RETENTION.minusSeconds(1));

        assertEquals(200, service.getDocumentAnalysis(jobId).getStatus());
    }

    @Test
    void expiredJobReadsAsInvalid() {
        MutableClock clock = new MutableClock(START);
        TextractService service = new TextractService(new ObjectMapper(), mock(AiMockConfigLoader.class), clock);
        String jobId = startJob(service, "TEXT_DETECTION");

        clock.advance(TextractService.RESULT_RETENTION.plusSeconds(1));

        AwsException exception = assertThrows(AwsException.class,
                () -> service.getDocumentTextDetection(jobId));
        assertEquals("InvalidJobIdException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    private static String startJob(TextractService service, String jobType) {
        ObjectNode response = jobType.equals("DOCUMENT_ANALYSIS")
                ? (ObjectNode) service.startDocumentAnalysis().getEntity()
                : (ObjectNode) service.startDocumentTextDetection().getEntity();
        return response.get("JobId").asText();
    }

    private static TextractService serviceAt(Instant instant) {
        return new TextractService(new ObjectMapper(), mock(AiMockConfigLoader.class), new MutableClock(instant));
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}