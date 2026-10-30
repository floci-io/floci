package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SecretsManagerEventPublisherTest {
    private static final String REGION = "us-east-1";
    private static final String ARN = "arn:aws:secretsmanager:us-east-1:661100000003:secret:example";
    private final ObjectMapper mapper = new ObjectMapper();
    private CloudTrailService trails;
    private EventBridgeService events;
    private SecretsManagerEventPublisher publisher;

    @BeforeEach
    void setUp() {
        trails = mock(CloudTrailService.class);
        events = mock(EventBridgeService.class);
        AccountResolver accounts = mock(AccountResolver.class);
        when(accounts.extractAccessKeyId("signed-request")).thenReturn("example-access-key");
        publisher = new SecretsManagerEventPublisher(trails, events, accounts, mapper);
    }

    @Test
    void onlyAllowlistedMetadataReachesCloudTrailAndEventBridge() throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {"SecretId":"example","ClientRequestToken":"example-token","VersionStages":["AWSCURRENT"],
                 "SecretString":"sensitive-string","SecretBinary":"sensitive-binary",
                 "Description":"sensitive-description","unknown":{"password":"sensitive-password"}}
                """);
        ObjectNode response = mapper.createObjectNode().put("ARN", ARN).put("Name", "example").put("VersionId", "version");
        ObjectNode record = mapper.createObjectNode().put("eventName", "PutSecretValue");
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
        verify(events).publishServiceEvent("aws.secretsmanager", "AWS API Call via CloudTrail",
                record.toString(), ARN, REGION);
    }

    @Test
    void noMatchingLoggingTrailMeansNoEventBridgePublication() {
        when(trails.emitManagementEvent(any())).thenReturn(Optional.empty());
        publisher.secretWritten("UpdateSecret", mapper.createObjectNode(),
                mapper.createObjectNode().put("ARN", ARN), REGION, null);
        verifyNoInteractions(events);
    }

    @Test
    void notificationFailureDoesNotFailTheAlreadyCommittedWrite() {
        when(trails.emitManagementEvent(any())).thenThrow(new IllegalStateException("example failure"));
        assertDoesNotThrow(() -> publisher.secretWritten("PutSecretValue", mapper.createObjectNode(),
                mapper.createObjectNode().put("ARN", ARN), REGION, null));
    }
}
