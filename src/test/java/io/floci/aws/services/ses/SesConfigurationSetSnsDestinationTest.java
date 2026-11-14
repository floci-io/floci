package io.floci.aws.services.ses;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.ses.SesEventPublisher.TopicAccess;
import io.floci.aws.services.ses.model.ConfigurationSet;
import io.floci.aws.services.ses.model.EventDestination;
import io.floci.aws.services.ses.model.SnsDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The SNS topic of an event destination is checked when it is set; messages and order were probed. */
class SesConfigurationSetSnsDestinationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String CS = "events";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:events";
    private static final String OTHER_TOPIC = "arn:aws:sns:us-east-1:000000000000:other";

    private SesEventPublisher publisher;
    private SesConfigurationSetService service;

    @BeforeEach
    void setUp() {
        publisher = mock(SesEventPublisher.class);
        when(publisher.eventDestinationTopicAccess(any(), any(), any(), any())).thenReturn(TopicAccess.NOT_FOUND);
        when(publisher.eventDestinationTopicAccess(TOPIC, CS, ACCOUNT_ID, REGION)).thenReturn(TopicAccess.PUBLISHABLE);
        service = new SesConfigurationSetService(new InMemoryStorage<>(), publisher);
        ConfigurationSet configurationSet = new ConfigurationSet();
        configurationSet.setName(CS);
        service.create(configurationSet, REGION);
    }

    @Test
    void publishableTopicIsStored() {
        create("to-sns", TOPIC, false);

        assertEquals(TOPIC, storedTopic());
    }

    @Test
    void paddedArnOfAPublishableTopicIsRefusedAndNotStored() {
        String padded = "  " + TOPIC + "  ";

        assertEquals("Invalid SNSDestination", refusal(() -> create("to-sns", padded, false)).getMessage());
        AwsException v2 = assertThrows(AwsException.class, () -> create("to-sns", padded, true));
        assertEquals("BadRequestException", v2.getErrorCode());
        assertEquals("1 validation error detected: Value '" + padded + "' at 'destinations.1.member.snsTopicArn' "
                + "failed to satisfy constraint: Member must satisfy regular expression pattern: "
                + "arn:aws[^:]*:sns:[a-zA-Z0-9\\-]*:\\d{12}:[a-zA-Z_0-9+=,.@\\-_/:]+", v2.getMessage());
        assertEquals(List.of(), service.getEventDestinations(CS, REGION));
    }

    @Test
    void paddedArnOfAMissingTopicIsReportedWithoutItsPadding() {
        assertRefused("SNS topic <" + OTHER_TOPIC + "> not found.", " " + OTHER_TOPIC + " ");
    }

    @Test
    void missingTopicIsRefused() {
        assertRefused("SNS topic <" + OTHER_TOPIC + "> not found.", OTHER_TOPIC);
    }

    @Test
    void topicThatRefusesSesIsRefused() {
        when(publisher.eventDestinationTopicAccess(OTHER_TOPIC, CS, ACCOUNT_ID, REGION))
                .thenReturn(TopicAccess.DENIED);

        assertRefused("Access denied to SNS topic <" + OTHER_TOPIC
                + ">. Verify the SNS topic policy grants Amazon SES the sns:Publish permission.", OTHER_TOPIC);
    }

    @Test
    void valueThatIsNotATopicArnIsRefused() {
        String form = " should have the form <arn:aws:sns:region:[account-id]:[topic-name]>";

        assertRefused("Invalid SNS topic ARN <not-an-arn>" + form, "not-an-arn");
        assertRefused("Invalid SNS topic ARN <arn:aws:sqs:us-east-1:000000000000:x>" + form,
                "arn:aws:sqs:us-east-1:000000000000:x");
        assertRefused("Invalid SNS topic ARN <>" + form, "   ");
        for (String arn : List.of("arn:aws:sns:us-east-1:abc:t", "arn:aws:sns:us-east-1:12345678901:t",
                "arn:aws:sns:us-east-1::t", "arn:aws:sns:US-EAST-1:000000000000:t",
                "arn:foo:sns:us-east-1:000000000000:t")) {
            assertRefused("Invalid SNS topic ARN <" + arn + ">" + form, arn);
        }
    }

    @Test
    void topicArnWithoutARegionIsATopicOfAnotherRegion() {
        assertRefused("SNS topic must be located in same region as SES <us-east-1>",
                "arn:aws:sns::000000000000:events");
        assertRefused("SNS topic must be located in same region as SES <us-east-1>",
                "arn:aws:sns:not-a-region:000000000000:events");
    }

    @Test
    void topicArnSnsWouldNotAcceptIsRefused() {
        for (String arn : List.of("arn:aws:sns:us-east-1:000000000000:", "arn:aws:sns:us-east-1:000000000000:bad name!",
                "arn:aws-cn:sns:us-east-1:000000000000:events")) {
            assertRefused("Invalid parameter for SNS topic <" + arn
                    + ">. Verify the SNS topic configuration is correct.", arn);
        }
    }

    @Test
    void topicOfAnotherRegionIsRefusedWithoutALookup() {
        assertRefused("SNS topic must be located in same region as SES <us-east-1>",
                "arn:aws:sns:us-west-2:000000000000:events");

        verify(publisher, never()).eventDestinationTopicAccess(any(), any(), any(), any());
    }

    @Test
    void emptyAndMissingTopicArnHaveTheirOwnMessagesPerApi() {
        assertEquals("Invalid ARN provided: topic ARN = .", refusal(() -> create("to-sns", "", false)).getMessage());
        assertEquals("Invalid topic ARN provided: .", refusal(() -> create("to-sns", "", true)).getMessage());
        assertEquals("Invalid topic ARN provided: null.", refusal(() -> create("to-sns", null, true)).getMessage());
    }

    @Test
    void disabledDestinationIsCheckedToo() {
        EventDestination destination = destination(OTHER_TOPIC);
        destination.setEnabled(false);

        AwsException e = refusal(() -> service.createEventDestination(CS, "to-sns", destination, ACCOUNT_ID,
                REGION, false));

        assertEquals("SNS topic <" + OTHER_TOPIC + "> not found.", e.getMessage());
    }

    @Test
    void v1CreateLooksUpTheSetAndTheNameBeforeTheTopic() {
        create("to-sns", TOPIC, false);

        assertEquals("ConfigurationSetDoesNotExist", assertThrows(AwsException.class,
                () -> service.createEventDestination("ghost", "to-sns", destination(OTHER_TOPIC), ACCOUNT_ID,
                        REGION, false)).getErrorCode());
        assertEquals("AlreadyExists", assertThrows(AwsException.class,
                () -> create("to-sns", OTHER_TOPIC, false)).getErrorCode());
    }

    @Test
    void v2CreateChecksTheTopicBeforeTheSetAndTheName() {
        create("to-sns", TOPIC, true);

        assertEquals("InvalidSNSDestination", assertThrows(AwsException.class,
                () -> service.createEventDestination("ghost", "to-sns", destination(OTHER_TOPIC), ACCOUNT_ID,
                        REGION, true)).getErrorCode());
        assertEquals("InvalidSNSDestination", assertThrows(AwsException.class,
                () -> create("to-sns", OTHER_TOPIC, true)).getErrorCode());
    }

    @Test
    void updateLooksUpTheDestinationBeforeTheTopic() {
        for (boolean v2 : new boolean[] {false, true}) {
            assertEquals("NotFoundException", assertThrows(AwsException.class,
                    () -> service.updateEventDestination(CS, "unknown", destination(OTHER_TOPIC), ACCOUNT_ID,
                            REGION, v2)).getErrorCode());
        }
    }

    @Test
    void refusedUpdateKeepsTheStoredDestination() {
        create("to-sns", TOPIC, false);

        AwsException e = refusal(() -> service.updateEventDestination(CS, "to-sns", destination(OTHER_TOPIC),
                ACCOUNT_ID, REGION, false));

        assertEquals("SNS topic <" + OTHER_TOPIC + "> not found.", e.getMessage());
        assertEquals(TOPIC, storedTopic());
    }

    private void assertRefused(String message, String topicArn) {
        for (boolean v2 : new boolean[] {false, true}) {
            assertEquals(message, refusal(() -> create("to-sns", topicArn, v2)).getMessage());
        }
    }

    private static AwsException refusal(Runnable call) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals("InvalidSNSDestination", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        return e;
    }

    private void create(String name, String topicArn, boolean v2) {
        service.createEventDestination(CS, name, destination(topicArn), ACCOUNT_ID, REGION, v2);
    }

    private String storedTopic() {
        return service.getEventDestinations(CS, REGION).get(0).getSnsDestination().getTopicArn();
    }

    private static EventDestination destination(String topicArn) {
        EventDestination destination = new EventDestination();
        destination.setEnabled(true);
        destination.setMatchingEventTypes(List.of("SEND"));
        SnsDestination sns = new SnsDestination();
        sns.setTopicArn(topicArn);
        destination.setSnsDestination(sns);
        return destination;
    }
}
