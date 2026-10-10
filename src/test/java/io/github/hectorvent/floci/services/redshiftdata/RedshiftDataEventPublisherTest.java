package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedshiftDataEventPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishesAwsEnvelopeInResourceAccountAndRegion() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EventBridgeService events = mock(EventBridgeService.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.isServiceEnabled("eventbridge")).thenReturn(true);
        when(events.putEvents(anyList(), anyString(), anyString()))
                .thenReturn(new EventBridgeService.PutEventsResult(0, List.of()));
        RedshiftDataEventPublisher publisher = new RedshiftDataEventPublisher(events, mapper, registry);
        RedshiftDataStatementStore.StoredStatement statement = new RedshiftDataStatementStore.StoredStatement();
        statement.id = "statement-id";
        statement.accountId = "111111111111";
        statement.region = "cn-north-1";
        statement.resourceArn = "arn:aws-cn:redshift-serverless:cn-north-1:111111111111:workgroup/group-id";
        statement.principal = "arn:aws-cn:iam::111111111111:role/etl";
        statement.statementName = "etl";
        statement.status = RedshiftDataStatementStore.Status.FINISHED;
        statement.resultRows = 3;
        statement.expiresAt = Instant.parse("2026-10-11T00:00:00Z");
        publisher.publish(statement);
        ArgumentCaptor<List<Map<String, Object>>> entries = ArgumentCaptor.forClass(List.class);
        verify(events).putEvents(entries.capture(), eq("cn-north-1"), eq("111111111111"));
        Map<String, Object> entry = entries.getValue().getFirst();
        assertEquals("aws.redshift-data", entry.get("Source"));
        assertEquals("Redshift Data Statement Status Change", entry.get("DetailType"));
        assertEquals(statement.resourceArn, mapper.valueToTree(entry.get("Resources")).get(0).asText());
        ObjectNode detail = (ObjectNode) mapper.readTree((String) entry.get("Detail"));
        assertEquals("FINISHED", detail.path("state").asText());
        assertEquals(3, detail.path("rows").asInt());
        assertEquals(statement.principal, detail.path("principal").asText());
        assertEquals(statement.expiresAt.getEpochSecond(), detail.path("expireAt").asLong());
        assertEquals(statement.id.hashCode() & Integer.MAX_VALUE, detail.path("redshiftQueryId").asLong());
    }

    @Test
    void failedEntriesAndExceptionsDoNotFailSqlCompletion() {
        EventBridgeService events = mock(EventBridgeService.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.isServiceEnabled("eventbridge")).thenReturn(true);
        RedshiftDataEventPublisher publisher = new RedshiftDataEventPublisher(events, new ObjectMapper(), registry);
        RedshiftDataStatementStore.StoredStatement statement = new RedshiftDataStatementStore.StoredStatement();
        statement.id = "id";
        statement.accountId = "111111111111";
        statement.region = "us-east-1";
        statement.status = RedshiftDataStatementStore.Status.FAILED;
        statement.expiresAt = Instant.now();
        when(events.putEvents(anyList(), anyString(), anyString()))
                .thenReturn(new EventBridgeService.PutEventsResult(1, List.of(Map.of("ErrorCode", "InternalFailure"))))
                .thenThrow(new IllegalStateException("delivery failure"));
        assertDoesNotThrow(() -> publisher.publish(statement));
        assertDoesNotThrow(() -> publisher.publish(statement));
    }
}
