package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.ExclusionAlreadyExistsException;
import software.amazon.awssdk.services.ecr.model.ExclusionNotFoundException;
import software.amazon.awssdk.services.ecr.model.InvalidParameterException;
import software.amazon.awssdk.services.ecr.model.LimitExceededException;
import software.amazon.awssdk.services.ecr.model.ListPullTimeUpdateExclusionsResponse;
import software.amazon.awssdk.services.ecr.model.RegisterPullTimeUpdateExclusionResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EcrPullTimeExclusionsIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(741683100000L);
    private static final String REGION = "eu-west-2";
    private static final String ROLE = "arn:aws:iam::123456789012:role/scanners/Reader";
    private String account;
    private EcrClient client;

    @BeforeAll
    static void configureAwsJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void openClient() {
        account = String.valueOf(ACCOUNTS.incrementAndGet());
        client = AwsTestClients.create(EcrClient.builder(), account, Region.of(REGION));
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void lifecycleUsesModeledErrorsAndSdkTimestamp() {
        Instant before = Instant.now().minusSeconds(1);
        RegisterPullTimeUpdateExclusionResponse created = client.registerPullTimeUpdateExclusion(r -> r.principalArn(ROLE));
        assertEquals(ROLE, created.principalArn());
        assertNotNull(created.createdAt());
        assertFalse(created.createdAt().isBefore(before));
        assertFalse(created.createdAt().isAfter(Instant.now().plusSeconds(1)));
        assertThrows(ExclusionAlreadyExistsException.class, () -> client.registerPullTimeUpdateExclusion(r -> r.principalArn(ROLE)));
        assertEquals(List.of(ROLE), client.listPullTimeUpdateExclusions(r -> {}).pullTimeUpdateExclusions());
        assertEquals(ROLE, client.deregisterPullTimeUpdateExclusion(r -> r.principalArn(ROLE)).principalArn());
        assertThrows(ExclusionNotFoundException.class, () -> client.deregisterPullTimeUpdateExclusion(r -> r.principalArn(ROLE)));
        assertTrue(client.listPullTimeUpdateExclusions(r -> {}).pullTimeUpdateExclusions().isEmpty());
    }

    @Test
    void pagesResumeAfterCursorDeletionAndAreScoped() {
        List<String> arns = List.of(ROLE, "arn:aws:iam::123456789012:user/team/Scanner", "arn:aws:iam::123456789012:role/A");
        arns.forEach(arn -> client.registerPullTimeUpdateExclusion(r -> r.principalArn(arn)));
        List<String> sorted = arns.stream().sorted().toList();
        ListPullTimeUpdateExclusionsResponse first = client.listPullTimeUpdateExclusions(r -> r.maxResults(1));
        assertEquals(sorted.subList(0, 1), first.pullTimeUpdateExclusions());
        assertNotNull(first.nextToken());
        client.deregisterPullTimeUpdateExclusion(r -> r.principalArn(sorted.getFirst()));
        ListPullTimeUpdateExclusionsResponse last = client.listPullTimeUpdateExclusions(r -> r.maxResults(2).nextToken(first.nextToken()));
        assertEquals(sorted.subList(1, 3), last.pullTimeUpdateExclusions());
        assertNull(last.nextToken());
        try (EcrClient other = AwsTestClients.create(EcrClient.builder(), "741683199999", Region.of(REGION));
             EcrClient region = AwsTestClients.create(EcrClient.builder(), account, Region.US_EAST_1)) {
            assertTrue(other.listPullTimeUpdateExclusions(r -> {}).pullTimeUpdateExclusions().isEmpty());
            assertTrue(region.listPullTimeUpdateExclusions(r -> {}).pullTimeUpdateExclusions().isEmpty());
            assertThrows(InvalidParameterException.class, () -> other.listPullTimeUpdateExclusions(r -> r.nextToken(first.nextToken())));
            assertThrows(InvalidParameterException.class, () -> region.listPullTimeUpdateExclusions(r -> r.nextToken(first.nextToken())));
        }
    }

    @Test
    void registryAllowsOneHundredExclusionsAndFreedSlotsCanBeReused() {
        List<String> arns = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String arn = "arn:aws:iam::123456789012:role/Scanner" + i;
            client.registerPullTimeUpdateExclusion(r -> r.principalArn(arn));
            arns.add(arn);
        }
        assertEquals(100, client.listPullTimeUpdateExclusions(r -> {}).pullTimeUpdateExclusions().size());
        ListPullTimeUpdateExclusionsResponse all = client.listPullTimeUpdateExclusions(r -> r.maxResults(1000));
        assertEquals(arns.stream().sorted().toList(), all.pullTimeUpdateExclusions());
        assertNull(all.nextToken());
        assertThrows(ExclusionAlreadyExistsException.class, () -> client.registerPullTimeUpdateExclusion(r -> r.principalArn(arns.getFirst())));
        assertThrows(LimitExceededException.class, () -> client.registerPullTimeUpdateExclusion(r -> r.principalArn(ROLE)));
        client.deregisterPullTimeUpdateExclusion(r -> r.principalArn(arns.getFirst()));
        assertEquals(ROLE, client.registerPullTimeUpdateExclusion(r -> r.principalArn(ROLE)).principalArn());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "arn:aws:iam::12:role/X", "arn:aws:iam::123456789012:group/Readers",
            "arn:aws:sts::123456789012:assumed-role/X/Y", "arn:aws:iam:us-east-1:123456789012:role/X",
            "arn:aws:iam::123456789012:role/trailing/"})
    void invalidPrincipalsAreRejected(String arn) {
        assertThrows(InvalidParameterException.class, () -> client.registerPullTimeUpdateExclusion(r -> r.principalArn(arn)));
        assertThrows(InvalidParameterException.class, () -> client.deregisterPullTimeUpdateExclusion(r -> r.principalArn(arn)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"maxResults\":0}", "{\"maxResults\":-1}", "{\"maxResults\":1001}",
            "{\"maxResults\":2147483648}", "{\"maxResults\":1.5}", "{\"maxResults\":\"1\"}",
            "{\"nextToken\":3}", "{\"nextToken\":{}}", "{\"nextToken\":\"%%%\"}"})
    void malformedListInputsReturnClientErrors(String body) {
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921.ListPullTimeUpdateExclusions")
                .body(body).post("/").then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }
}
