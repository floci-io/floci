package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SecretsManagerEventPublisherTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "661100000003";
    private static final String ARN = "arn:aws:secretsmanager:us-east-1:661100000003:secret:example";
    private final ObjectMapper mapper = new ObjectMapper();
    private CloudTrailService trails;
    private EventBridgeService events;
    private SecretsManagerEventPublisher publisher;
    private Deque<Runnable> deliveries;

    @BeforeEach
    void setUp() {
        trails = mock(CloudTrailService.class);
        events = mock(EventBridgeService.class);
        AccountResolver accounts = mock(AccountResolver.class);
        when(accounts.extractAccessKeyId("signed-request")).thenReturn("example-access-key");
        deliveries = new ArrayDeque<>();
        publisher = new SecretsManagerEventPublisher(trails, events, accounts, mapper, deliveries::addLast);
    }

    @Test
    void onlyAllowlistedMetadataReachesCloudTrailAndEventBridge() throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {"SecretId":"example","ClientRequestToken":"example-token","VersionStages":["AWSCURRENT"],
                 "SecretString":"sensitive-string","SecretBinary":"sensitive-binary",
                 "Description":"sensitive-description","unknown":{"password":"sensitive-password"}}
                """);
        ObjectNode response = mapper.createObjectNode().put("ARN", ARN).put("Name", "example").put("VersionId", "version");
        ObjectNode record = record();
        when(trails.emitManagementEvent(any())).thenReturn(Optional.of(record));

        publisher.secretWritten("PutSecretValue", request, response, REGION, "signed-request");

        ArgumentCaptor<CloudTrailService.ManagementEventInput> capture =
                ArgumentCaptor.forClass(CloudTrailService.ManagementEventInput.class);
        verify(trails).emitManagementEvent(capture.capture());
        CloudTrailService.ManagementEventInput input = capture.getValue();
        assertEquals("secretsmanager.amazonaws.com", input.eventSource());
        assertEquals("example-access-key", input.accessKeyId());
        assertEquals("AWSCURRENT", input.requestParameters().path("versionStages").get(0).asText());
        assertEquals(3, input.requestParameters().size());
        assertEquals(ARN, input.responseElements().path("arn").asText());
        assertFalse(input.requestParameters().toString().contains("sensitive-"));
        verifyNoInteractions(events);
        assertEquals(1, deliveries.size());
        deliveries.removeFirst().run();
        verify(events).publishServiceEvent("aws.secretsmanager", "AWS API Call via CloudTrail",
                record.toString(), ARN, REGION);
    }

    @Test
    void noMatchingLoggingTrailMeansNoEventBridgePublication() {
        when(trails.emitManagementEvent(any())).thenReturn(Optional.empty());
        publisher.secretWritten("UpdateSecret", mapper.createObjectNode(),
                mapper.createObjectNode().put("ARN", ARN), REGION, null);
        verifyNoInteractions(events);
        assertTrue(deliveries.isEmpty());
    }

    @Test
    void notificationFailureDoesNotFailTheAlreadyCommittedWrite() {
        when(trails.emitManagementEvent(any())).thenThrow(new IllegalStateException("example failure"));
        assertDoesNotThrow(() -> publisher.secretWritten("PutSecretValue", mapper.createObjectNode(),
                mapper.createObjectNode().put("ARN", ARN), REGION, null));
    }

    @Test
    void queuedDeliveryUsesSnapshotsInsteadOfMutableRequestObjects() {
        ObjectNode record = record();
        String detail = record.toString();
        ObjectNode response = mapper.createObjectNode().put("ARN", ARN);
        when(trails.emitManagementEvent(any())).thenReturn(Optional.of(record));
        publisher.secretWritten("PutSecretValue", mapper.createObjectNode(), response, REGION, null);
        record.removeAll();
        response.put("ARN", "changed-after-enqueue");
        deliveries.removeFirst().run();
        verify(events).publishServiceEvent("aws.secretsmanager", "AWS API Call via CloudTrail", detail, ARN, REGION);
    }

    @Test
    void rejectedQueueDoesNotRunDeliveryInTheWriteRequestOrFailTheWrite() {
        when(trails.emitManagementEvent(any())).thenReturn(Optional.of(record()));
        publisher = new SecretsManagerEventPublisher(trails, events, new AccountResolver(ACCOUNT), mapper,
                task -> { throw new RejectedExecutionException("example full queue"); });
        assertDoesNotThrow(() -> publisher.secretWritten("PutSecretValue", mapper.createObjectNode(),
                mapper.createObjectNode().put("ARN", ARN), REGION, null));
        verifyNoInteractions(events);
    }

    @Test
    void backgroundDeliveryFailureIsContained() {
        when(trails.emitManagementEvent(any())).thenReturn(Optional.of(record()));
        doThrow(new IllegalStateException("example delivery failure")).when(events)
                .publishServiceEvent(anyString(), anyString(), anyString(), anyString(), anyString());
        publisher.secretWritten("PutSecretValue", mapper.createObjectNode(),
                mapper.createObjectNode().put("ARN", ARN), REGION, null);
        assertDoesNotThrow(() -> deliveries.removeFirst().run());
    }

    private ObjectNode record() {
        return mapper.createObjectNode().put("eventName", "PutSecretValue")
                .put("recipientAccountId", ACCOUNT).put("awsRegion", REGION);
    }
}
