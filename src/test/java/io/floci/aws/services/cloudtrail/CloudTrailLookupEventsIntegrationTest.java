package io.floci.aws.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Event;
import software.amazon.awssdk.services.cloudtrail.model.InvalidLookupAttributesException;
import software.amazon.awssdk.services.cloudtrail.model.InvalidNextTokenException;
import software.amazon.awssdk.services.cloudtrail.model.LookupAttributeKey;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsResponse;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Event history: {@code LookupEvents} returns the management events of the caller's account and
 * region, newest first, with no trail needed, filtered by one lookup attribute and a time range.
 * Each test runs in its own account so it sees only the events it caused.
 */
@QuarkusTest
class CloudTrailLookupEventsIntegrationTest {

    private static final String SECRETS_MANAGER = "secretsmanager.amazonaws.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void aManagementWriteIsInTheEventHistoryWithoutATrail() throws Exception {
        String account = newAccount();
        String secret = putSecretValues(account, 1);

        List<Event> events = lookup(account, LookupAttributeKey.EVENT_SOURCE, SECRETS_MANAGER, null).events();

        assertEquals(1, events.size());
        Event event = events.getFirst();
        assertEquals("PutSecretValue", event.eventName());
        assertEquals(SECRETS_MANAGER, event.eventSource());
        assertEquals("false", event.readOnly());
        assertNotNull(event.eventTime());
        JsonNode record = MAPPER.readTree(event.cloudTrailEvent());
        assertEquals(event.eventId(), record.path("eventID").asText());
        assertEquals("PutSecretValue", record.path("eventName").asText());
        assertEquals(account, record.path("recipientAccountId").asText());
        assertEquals(record.path("userIdentity").path("userName").asText(), event.username());
        assertTrue(record.path("requestParameters").path("secretId").asText().contains(secret));
    }

    @Test
    void aLookupAttributeSelectsOnlyMatchingEvents() {
        String account = newAccount();
        putSecretValues(account, 1);

        assertEquals(0, lookup(account, LookupAttributeKey.EVENT_NAME, "UpdateSecret", null).events().size());
        assertEquals(1, lookup(account, LookupAttributeKey.EVENT_NAME, "PutSecretValue", null).events().size());
        assertEquals(0, lookup(account, LookupAttributeKey.EVENT_SOURCE, "s3.amazonaws.com", null).events().size());
    }

    @Test
    void aTimeRangeSelectsOnlyEventsInsideIt() {
        String account = newAccount();
        Instant before = Instant.now().minusSeconds(60);
        putSecretValues(account, 1);
        Instant after = Instant.now().plusSeconds(60);

        try (CloudTrailClient cloudTrail = cloudTrail(account)) {
            assertEquals(1, cloudTrail.lookupEvents(r -> r.startTime(before).endTime(after)).events().size());
            assertEquals(0, cloudTrail.lookupEvents(r -> r.startTime(after)).events().size());
            assertEquals(0, cloudTrail.lookupEvents(r -> r.endTime(before)).events().size());
        }
    }

    @Test
    void eventsArePagedNewestFirst() {
        String account = newAccount();
        putSecretValues(account, 3);

        LookupEventsResponse first = lookup(account, LookupAttributeKey.EVENT_SOURCE, SECRETS_MANAGER, 2);
        assertEquals(2, first.events().size());
        assertNotNull(first.nextToken());
        assertTrue(!first.events().get(0).eventTime().isBefore(first.events().get(1).eventTime()));

        try (CloudTrailClient cloudTrail = cloudTrail(account)) {
            LookupEventsResponse second = cloudTrail.lookupEvents(r -> r.maxResults(2).nextToken(first.nextToken())
                    .lookupAttributes(a -> a.attributeKey(LookupAttributeKey.EVENT_SOURCE).attributeValue(SECRETS_MANAGER)));
            assertEquals(1, second.events().size());
            assertNull(second.nextToken());
            assertTrue(!first.events().get(1).eventTime().isBefore(second.events().getFirst().eventTime()));
        }
    }

    @Test
    void aPageHoldsAtMostFiftyEventsWhateverMaxResultsAsks() {
        String account = newAccount();
        putSecretValues(account, 51);

        LookupEventsResponse page = lookup(account, LookupAttributeKey.EVENT_SOURCE, SECRETS_MANAGER, 1000);

        assertEquals(50, page.events().size());
        assertNotNull(page.nextToken());
    }

    @Test
    void aNextTokenIsRefusedForADifferentLookup() {
        String account = newAccount();
        putSecretValues(account, 2);
        String token = lookup(account, LookupAttributeKey.EVENT_SOURCE, SECRETS_MANAGER, 1).nextToken();
        assertNotNull(token);

        try (CloudTrailClient cloudTrail = cloudTrail(account)) {
            assertThrows(InvalidNextTokenException.class, () -> cloudTrail.lookupEvents(r -> r.maxResults(1)
                    .nextToken(token)
                    .lookupAttributes(a -> a.attributeKey(LookupAttributeKey.EVENT_NAME).attributeValue("PutSecretValue"))));
        }
    }

    @Test
    void moreThanOneLookupAttributeIsRefused() {
        try (CloudTrailClient cloudTrail = cloudTrail(newAccount())) {
            assertThrows(InvalidLookupAttributesException.class, () -> cloudTrail.lookupEvents(r -> r.lookupAttributes(
                    a -> a.attributeKey(LookupAttributeKey.EVENT_NAME).attributeValue("PutSecretValue"),
                    a -> a.attributeKey(LookupAttributeKey.EVENT_SOURCE).attributeValue(SECRETS_MANAGER))));
        }
    }

    private String putSecretValues(String account, int writes) {
        String name = "history-" + UUID.randomUUID();
        try (SecretsManagerClient secrets = AwsTestClients.create(SecretsManagerClient.builder(), account,
                Region.US_EAST_1, endpoint)) {
            secrets.createSecret(r -> r.name(name).secretString("v0"));
            for (int i = 1; i <= writes; i++) {
                String value = "v" + i;
                secrets.putSecretValue(r -> r.secretId(name).secretString(value));
            }
        }
        return name;
    }

    private LookupEventsResponse lookup(String account, LookupAttributeKey key, String value, Integer maxResults) {
        try (CloudTrailClient cloudTrail = cloudTrail(account)) {
            return cloudTrail.lookupEvents(r -> r.maxResults(maxResults)
                    .lookupAttributes(a -> a.attributeKey(key).attributeValue(value)));
        }
    }

    private CloudTrailClient cloudTrail(String account) {
        return AwsTestClients.create(CloudTrailClient.builder(), account, Region.US_EAST_1, endpoint);
    }

    private static String newAccount() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(100_000_000_000L, 1_000_000_000_000L));
    }
}
