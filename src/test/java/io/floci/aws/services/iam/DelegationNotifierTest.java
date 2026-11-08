package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ServiceCallAuthorizer;
import io.floci.aws.services.iam.model.DelegationRequest;
import io.floci.aws.services.iam.model.DelegationRequestState;
import io.floci.aws.services.sns.SnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two notification shapes a delegation request produces, and what decides whether one is
 * published at all.
 *
 * <p>The fields and their types come from the guide's two tables, and the timestamps from its two
 * worked examples, which carry ISO 8601 to the millisecond.
 */
class DelegationNotifierTest {

    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000001:partner-notifications";
    private static final String PARTNER = "000000000001";
    private static final String CUSTOMER = "000000000002";
    private static final String REQUEST_ID = "dr-0123456789abcdef";
    private static final Instant MOVED_AT = Instant.parse("2026-01-15T10:30:00.123Z");
    /** The eight hours the request below asks for, which is what its token's expiry is measured from. */
    private static final int SESSION_DURATION = 28800;

    private SnsService sns;
    private ServiceCallAuthorizer authorizer;
    private DelegationNotifier notifier;

    @BeforeEach
    void setUp() {
        sns = mock(SnsService.class);
        authorizer = mock(ServiceCallAuthorizer.class);
        when(authorizer.authorize(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(IamPolicyEvaluator.Decision.ALLOW);
        notifier = new DelegationNotifier(sns, authorizer, new ObjectMapper());
    }

    private static DelegationRequest request(DelegationRequestState state, String ownerAccountId) {
        DelegationRequest request = new DelegationRequest();
        request.setDelegationRequestId(REQUEST_ID);
        request.setRequestorWorkflowId("workflow-12345");
        request.setRequestorId(PARTNER);
        request.setNotificationChannel(TOPIC);
        request.setOwnerAccountId(ownerAccountId);
        request.setSessionDuration(SESSION_DURATION);
        request.setState(state);
        request.setUpdatedTime(MOVED_AT);
        return request;
    }

    private static DelegationRequest released() {
        DelegationRequest request = request(DelegationRequestState.FINALIZED, CUSTOMER);
        request.setExchangeToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
        request.setTokenReleasedAt(MOVED_AT);
        return request;
    }

    /** The one message the topic received, parsed. */
    private JsonNode message() throws Exception {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sns).publish(eq(TOPIC), isNull(), message.capture(), isNull(), eq("us-east-1"));
        return new ObjectMapper().readTree(message.getValue());
    }

    private static List<String> fieldsOf(JsonNode message) {
        List<String> names = new ArrayList<>();
        Iterator<String> fields = message.fieldNames();
        while (fields.hasNext()) {
            names.add(fields.next());
        }
        return names;
    }

    @Test
    void aStateChangeCarriesTheFieldsEveryNotificationHas() throws Exception {
        notifier.publish(List.of(DelegationNotifier.Notice.stateChange(
                request(DelegationRequestState.ACCEPTED, CUSTOMER))));

        JsonNode message = message();
        assertEquals(List.of("Type", "RequestId", "RequestorWorkflowId", "State", "OwnerAccountId",
                "UpdatedAt"), fieldsOf(message));
        assertEquals("StateChange", message.path("Type").asText());
        assertEquals(REQUEST_ID, message.path("RequestId").asText());
        assertEquals("workflow-12345", message.path("RequestorWorkflowId").asText());
        assertEquals("ACCEPTED", message.path("State").asText());
        assertEquals(CUSTOMER, message.path("OwnerAccountId").asText());
        assertEquals("2026-01-15T10:30:00.123Z", message.path("UpdatedAt").asText());
    }

    @Test
    void theTokenNotificationAddsTheTokenAndWhenTheAccessExpires() throws Exception {
        notifier.publish(List.of(DelegationNotifier.Notice.exchangeToken(released())));

        JsonNode message = message();
        assertEquals(List.of("Type", "RequestId", "RequestorWorkflowId", "State", "OwnerAccountId",
                "UpdatedAt", "ExchangeToken", "ExpiresAt"), fieldsOf(message));
        assertEquals("ExchangeToken", message.path("Type").asText());
        assertEquals("FINALIZED", message.path("State").asText());
        assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
                message.path("ExchangeToken").asText());
        // The release instant plus SessionDuration, which is when every credential the token
        // buys expires, however late it is exchanged.
        assertEquals("2026-01-15T18:30:00.123Z", message.path("ExpiresAt").asText());
    }

    /**
     * A request carrying no customer account still expires, and the notification for it has
     * nobody to name, so the member is left out rather than sent as null.
     */
    @Test
    void aRequestWithNoCustomerAccountLeavesTheOwnerAccountOut() throws Exception {
        notifier.publish(List.of(DelegationNotifier.Notice.stateChange(
                request(DelegationRequestState.EXPIRED, null))));

        assertEquals(List.of("Type", "RequestId", "RequestorWorkflowId", "State", "UpdatedAt"),
                fieldsOf(message()));
    }

    /**
     * IAM publishes as itself, so the topic's own policy decides and the partner's own permissions
     * play no part. The guide has the partner allow {@code SNS:Publish} to
     * {@code iam.amazonaws.com}, and the request whose lifecycle triggered the publish is what the
     * confused-deputy conditions name.
     */
    @Test
    void theTopicPolicyIsAskedAboutIamAndTheRequestThatTriggeredIt() {
        notifier.publish(List.of(DelegationNotifier.Notice.stateChange(
                request(DelegationRequestState.ASSIGNED, CUSTOMER))));

        verify(authorizer).authorize("iam", "sns:Publish", TOPIC,
                "arn:aws:iam::" + PARTNER + ":delegation-request/" + REQUEST_ID, PARTNER);
    }

    @Test
    void aTopicThatRefusesIamReceivesNothing() {
        when(authorizer.authorize(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(IamPolicyEvaluator.Decision.DENY);

        notifier.publish(List.of(DelegationNotifier.Notice.stateChange(
                request(DelegationRequestState.ASSIGNED, CUSTOMER))));

        verifyNoInteractions(sns);
    }

    /**
     * The create refuses a channel that cannot name a topic, so a stored one naming none is a
     * request from before it did. There is still nothing to publish to and nothing to authorize,
     * and the transition it belongs to is not the notifier's to undo.
     */
    @Test
    void aChannelThatNamesNoTopicReachesNobody() {
        DelegationRequest request = request(DelegationRequestState.ASSIGNED, CUSTOMER);
        request.setNotificationChannel("partner-notifications");

        notifier.publish(List.of(DelegationNotifier.Notice.stateChange(request)));

        verifyNoInteractions(sns);
        verify(authorizer, never()).authorize(anyString(), anyString(), anyString(), any(), any());
    }

    /**
     * A notification that cannot be delivered costs the notification. The transition behind it has
     * already been written, and AWS rolls none back for a topic it could not reach: the failure it
     * describes is the partner not receiving the message. These go out from a finally, so an escape
     * would also replace whatever the call it was published from had already answered.
     */
    @Test
    void aPublishThatFailsDoesNotReachTheCaller() {
        when(sns.publish(eq(TOPIC), isNull(), anyString(), isNull(), anyString()))
                .thenThrow(new AwsException("NotFound", "Topic does not exist", 404));

        assertDoesNotThrow(() -> notifier.publish(List.of(DelegationNotifier.Notice.stateChange(
                request(DelegationRequestState.ASSIGNED, CUSTOMER)))));
    }

    /** A topic whose policy cannot even be read is the same answer: no notification, no error. */
    @Test
    void aPolicyThatCannotBeReadDoesNotReachTheCallerEither() {
        when(authorizer.authorize(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("malformed policy document"));

        assertDoesNotThrow(() -> notifier.publish(List.of(DelegationNotifier.Notice.stateChange(
                request(DelegationRequestState.ASSIGNED, CUSTOMER)))));
        verifyNoInteractions(sns);
    }

    /**
     * One notification failing does not cost the next one its attempt, which is what the pair
     * {@code FINALIZED} sends turns on: the token travels in the second of them.
     */
    @Test
    void aFailedNotificationDoesNotHoldUpTheNextOne() {
        when(sns.publish(eq(TOPIC), isNull(), anyString(), isNull(), anyString()))
                .thenThrow(new AwsException("InternalFailure", "transient", 500))
                .thenReturn("message-id");

        notifier.publish(List.of(DelegationNotifier.Notice.stateChange(released()),
                DelegationNotifier.Notice.exchangeToken(released())));

        verify(sns, times(2)).publish(eq(TOPIC), isNull(), anyString(), isNull(), anyString());
    }
}
