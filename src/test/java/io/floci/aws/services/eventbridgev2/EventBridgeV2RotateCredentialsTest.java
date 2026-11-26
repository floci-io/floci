package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridgev2.EventBridgeV2Webhooks.Answer;
import io.floci.aws.services.eventbridgev2.EventBridgeV2Webhooks.Request;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;
import io.floci.aws.services.eventbridgev2.model.RotationRequest;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.Secret;
import io.floci.aws.testing.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RotateWebhookCredentials against a real Secrets Manager service, with the messages, statuses and the order of
 * checks as measured on AWS.
 */
class EventBridgeV2RotateCredentialsTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final Instant NOW = Instant.parse("2026-11-25T06:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::000000000000:role/Hook";
    private static final String STATIC_AUTH = "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Tok\","
            + "\"SecretValue\":\"original-token\"}}";
    private static final String BASIC_AUTH = "{\"BasicHttpAuthConfiguration\":{\"Username\":\"alice\","
            + "\"Password\":\"original-pw\"}}";
    private static final String HMAC_AUTH = "{\"HmacAuthConfiguration\":{\"Algorithm\":\"SHA256\","
            + "\"Encoding\":\"HEX\",\"SignatureHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\","
            + "\"SecretValue\":\"0123456789abcdef\"}}";
    private static final String HMAC_BASE64_AUTH = "{\"HmacAuthConfiguration\":{\"Algorithm\":\"SHA256\","
            + "\"Encoding\":\"HEX\",\"SignatureHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\","
            + "\"SecretEncoding\":\"BASE64\",\"SecretValue\":\"MDEyMzQ1Njc4OWFiY2RlZg==\"}}";
    private static final String MISSING_SOURCE =
            "arn:aws:events:us-east-1:000000000000:event-sourcev2/webhook/nope/abcdefghijklmnopqrstuvwxy";
    private static final String UNION_MESSAGE =
            "Credential must set exactly one of StaticTokenSecretValue, BasicHttpPassword or HmacSecretValue";
    private static final String HMAC_RANGE_MESSAGE =
            "Credential.HmacSecretValue cannot be used with the authentication settings of this EventSource";
    private static final String HMAC_BASE64_MESSAGE = "Credential.HmacSecretValue must be standard Base64 when the "
            + "SecretEncoding of the EventSource is BASE64";

    private SecretsManagerService secrets;
    private EventBridgeV2Service service;
    private EventBridgeV2Webhooks webhooks;
    private ManualClock clock;
    private String bus;
    private int tokens;

    @BeforeEach
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("secretsmanager"), any(), any(TypeReference.class)))
                .thenAnswer(call -> AccountAwareStorageBackend.<Secret>inMemory(ACCOUNT));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().secretsmanager().defaultRecoveryWindowDays()).thenReturn(30);
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        secrets = new SecretsManagerService(factory, config, regionResolver, null, MAPPER, null);
        clock = new ManualClock(NOW);
        EventBridgeV2EventSources sources = new EventBridgeV2EventSources(
                AccountAwareStorageBackend.inMemory(ACCOUNT), secrets, regionResolver, clock, "http://floci.test:4566");
        service = new EventBridgeV2Service(AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT), regionResolver, mock(EventBridgeV2Delivery.class),
                sources, clock, true);
        webhooks = new EventBridgeV2Webhooks(service, sources, secrets, clock);
        bus = service.createEventBus(json("{\"Name\":\"orders\"}"), REGION).getArn();
    }

    // ──────────────────────────── The secret ────────────────────────────

    @Test
    void aSuppliedStaticTokenReplacesTheSecretAndKeepsTheOldOneAsPrevious() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        String first = versionOf(source, "AWSCURRENT");

        String secretArn = rotate(source, "{\"StaticTokenSecretValue\":\"second-token\"}");

        assertEquals(source.getSecretArn(), secretArn);
        assertEquals("second-token", valueOf(source, "AWSCURRENT"));
        assertEquals("original-token", valueOf(source, "AWSPREVIOUS"));
        assertEquals(first, versionOf(source, "AWSPREVIOUS"));
        assertEquals(Map.of("AWSCURRENT", versionOf(source, "AWSCURRENT"), "AWSPREVIOUS", first), stages(source));
    }

    @Test
    void aSecondRotationLeavesOnlyTheNewestTwoVersionsStaged() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        String first = versionOf(source, "AWSCURRENT");
        rotate(source, "{\"StaticTokenSecretValue\":\"second-token\"}");
        String second = versionOf(source, "AWSCURRENT");

        rotate(source, "{\"StaticTokenSecretValue\":\"third-token\"}");

        assertEquals("third-token", valueOf(source, "AWSCURRENT"));
        assertEquals("second-token", valueOf(source, "AWSPREVIOUS"));
        assertEquals(second, versionOf(source, "AWSPREVIOUS"));
        assertEquals(2, stages(source).size());
        assertEquals(List.of(), secrets.listSecretVersionIds(source.getSecretArn(), REGION).get(first));
    }

    @Test
    void noCredentialGeneratesA44CharacterValue() {
        EventSourceV2 source = source("hook", STATIC_AUTH);

        rotate(source, null);

        String generated = valueOf(source, "AWSCURRENT");
        assertEquals(44, generated.length());
        assertEquals(32, Base64.getDecoder().decode(generated).length);
        assertEquals("original-token", valueOf(source, "AWSPREVIOUS"));
    }

    @Test
    void aNullCredentialAndAKnownMemberWithANullOneAreAccepted() {
        EventSourceV2 source = source("hook", STATIC_AUTH);

        service.rotateWebhookCredentials(json("{\"EventSourceArn\":\"" + source.getArn()
                + "\",\"ClientToken\":\"t-null\",\"Credential\":null}"), REGION);
        assertEquals(44, valueOf(source, "AWSCURRENT").length());

        rotate(source, "{\"StaticTokenSecretValue\":\"second-token\",\"HmacSecretValue\":null}");
        assertEquals("second-token", valueOf(source, "AWSCURRENT"));
    }

    @Test
    void basicKeepsItsUsernameWhetherThePasswordIsSuppliedOrGenerated() throws Exception {
        EventSourceV2 source = source("hook", BASIC_AUTH);

        rotate(source, "{\"BasicHttpPassword\":\"pass:with:colon é\"}");
        JsonNode supplied = MAPPER.readTree(valueOf(source, "AWSCURRENT"));
        assertEquals("alice", supplied.get("username").textValue());
        assertEquals("pass:with:colon é", supplied.get("password").textValue());

        rotate(source, null);
        JsonNode generated = MAPPER.readTree(valueOf(source, "AWSCURRENT"));
        assertEquals("alice", generated.get("username").textValue());
        assertEquals(44, generated.get("password").textValue().length());
        assertNotEquals("pass:with:colon é", generated.get("password").textValue());
        assertEquals("{\"username\":\"alice\",\"password\":\"pass:with:colon é\"}", valueOf(source, "AWSPREVIOUS"));
    }

    @Test
    void basicWithAGeneratedUsernameKeepsTheGeneratedOne() throws Exception {
        EventSourceV2 source = source("hook", "{\"BasicHttpAuthConfiguration\":{}}");
        String username = MAPPER.readTree(valueOf(source, "AWSCURRENT")).get("username").textValue();

        rotate(source, "{\"BasicHttpPassword\":\"new-password\"}");

        assertEquals(username, MAPPER.readTree(valueOf(source, "AWSCURRENT")).get("username").textValue());
    }

    @Test
    void aRawHmacSecretIsStoredAsGivenWithItsPrefix() {
        EventSourceV2 source = source("hook", HMAC_AUTH);

        rotate(source, "{\"HmacSecretValue\":\"whsec_aaaaaaaaaa\"}");
        assertEquals("whsec_aaaaaaaaaa", valueOf(source, "AWSCURRENT"));

        rotate(source, "{\"HmacSecretValue\":\"" + "é".repeat(8) + "\"}");
        assertEquals("é".repeat(8), valueOf(source, "AWSCURRENT"));

        rotate(source, "{\"HmacSecretValue\":\"" + "é".repeat(256) + "\"}");
        assertEquals("é".repeat(256), valueOf(source, "AWSCURRENT"));
    }

    @Test
    void aRawHmacSecretOutsideSixteenToFiveHundredTwelveBytesIsRefused() {
        EventSourceV2 source = source("hook", HMAC_AUTH);

        assertRefused(source, "{\"HmacSecretValue\":\"aaaaaaaaaaaaaaa\"}", HMAC_RANGE_MESSAGE);
        assertRefused(source, "{\"HmacSecretValue\":\"" + "é".repeat(257) + "\"}", HMAC_RANGE_MESSAGE);
        rotate(source, "{\"HmacSecretValue\":\"aaaaaaaaaaaaaaaa\"}");
        assertEquals("aaaaaaaaaaaaaaaa", valueOf(source, "AWSCURRENT"));
    }

    @Test
    void aBase64HmacSecretLosesItsPrefixAndKeepsItsPadding() {
        EventSourceV2 source = source("hook", HMAC_BASE64_AUTH);

        rotate(source, "{\"HmacSecretValue\":\"whsec_a2tra2tra2tra2tra2traw==\"}");
        assertEquals("a2tra2tra2tra2tra2traw==", valueOf(source, "AWSCURRENT"));

        rotate(source, "{\"HmacSecretValue\":\"a2tra2tra2tra2tra2traw\"}");
        assertEquals("a2tra2tra2tra2tra2traw", valueOf(source, "AWSCURRENT"));
    }

    @Test
    void aBase64HmacSecretMustBeStandardBase64OfEnoughBytes() {
        EventSourceV2 source = source("hook", HMAC_BASE64_AUTH);

        assertRefused(source, "{\"HmacSecretValue\":\"a2tra2tra2tra2tra2tr_w==\"}", HMAC_BASE64_MESSAGE);
        assertRefused(source, "{\"HmacSecretValue\":\"a2tra2tra2tra2tra2tr\\naw==\"}", HMAC_BASE64_MESSAGE);
        assertRefused(source, "{\"HmacSecretValue\":\"" + Base64.getEncoder().encodeToString(new byte[15]) + "\"}",
                HMAC_RANGE_MESSAGE);
        assertRefused(source, "{\"HmacSecretValue\":\"whsec_" + Base64.getEncoder().encodeToString(new byte[15])
                + "\"}", HMAC_RANGE_MESSAGE);
        assertRefused(source, "{\"HmacSecretValue\":\"whsec_abcdef\"}", HMAC_RANGE_MESSAGE);
    }

    @Test
    void aGeneratedHmacSecretIs44Characters() {
        EventSourceV2 source = source("hook", HMAC_BASE64_AUTH);

        rotate(source, null);

        assertEquals(44, valueOf(source, "AWSCURRENT").length());
    }

    @Test
    void rotatingToTheValueThatIsAlreadyCurrentWritesNoVersion() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        Map<String, List<String>> before = secrets.listSecretVersionIds(source.getSecretArn(), REGION);

        rotate(source, "{\"StaticTokenSecretValue\":\"original-token\"}");

        assertEquals(before, secrets.listSecretVersionIds(source.getSecretArn(), REGION));
    }

    @Test
    void rotatingBasicToTheCurrentPasswordWritesNoVersion() {
        EventSourceV2 source = source("hook", BASIC_AUTH);
        Map<String, List<String>> before = secrets.listSecretVersionIds(source.getSecretArn(), REGION);

        rotate(source, "{\"BasicHttpPassword\":\"original-pw\"}");

        assertEquals(before, secrets.listSecretVersionIds(source.getSecretArn(), REGION));
    }

    @Test
    void rotatingHmacToTheCurrentValueWithItsPrefixWritesNoVersion() {
        EventSourceV2 source = source("hook", HMAC_BASE64_AUTH);
        Map<String, List<String>> before = secrets.listSecretVersionIds(source.getSecretArn(), REGION);

        rotate(source, "{\"HmacSecretValue\":\"whsec_MDEyMzQ1Njc4OWFiY2RlZg==\"}");

        assertEquals(before, secrets.listSecretVersionIds(source.getSecretArn(), REGION));
    }

    @Test
    void theSourceKeepsItsModifiedTimeUrlAndSecret() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        String url = service.webhookUrl(source);
        clock.advance(Duration.ofMinutes(5));

        rotate(source, "{\"StaticTokenSecretValue\":\"second-token\"}");

        EventSourceV2 described = service.describeEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\"}"),
                REGION);
        assertEquals(NOW, described.getLastModifiedTime());
        assertEquals(url, service.webhookUrl(described));
        assertEquals(source.getSecretArn(), described.getSecretArn());
    }

    @Test
    void anUpdateKeepsTheRememberedTokens() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        rotate(source, "{\"StaticTokenSecretValue\":\"second-token\"}");

        EventSourceV2 updated = service.updateEventSource(json("{\"EventSourceArn\":\"" + source.getArn()
                + "\",\"Description\":\"d\"}"), REGION);

        assertEquals(1, updated.getRotationRequests().size());
    }

    @Test
    void aSourceStoredWithoutTheTokenMapReadsAsEmpty() throws Exception {
        EventSourceV2 older = MAPPER.readValue("{\"name\":\"hook\",\"unknownMember\":1}", EventSourceV2.class);
        assertEquals(Map.of(), older.getRotationRequests());

        older.setRotationRequests(null);
        assertEquals(Map.of(), older.getRotationRequests());
    }

    // ──────────────────────────── Union and member rules ────────────────────────────

    @Test
    void anyCountOfMembersButOneIsRefused() {
        EventSourceV2 source = source("hook", STATIC_AUTH);

        assertRefused(source, "{}", UNION_MESSAGE);
        assertRefused(source, "{\"StaticTokenSecretValue\":null}", UNION_MESSAGE);
        assertRefused(source, "{\"Bogus\":\"secret-value\"}", UNION_MESSAGE);
        assertRefused(source, "{\"StaticTokenSecretValue\":\"second-token\",\"BasicHttpPassword\":\"second-pw\"}",
                UNION_MESSAGE);
        assertEquals("original-token", valueOf(source, "AWSCURRENT"));
    }

    @Test
    void theMemberMustBeTheOneOfTheAuthenticationType() {
        EventSourceV2 staticSource = source("tok", STATIC_AUTH);
        EventSourceV2 basicSource = source("bas", BASIC_AUTH);
        EventSourceV2 hmacSource = source("hmc", HMAC_AUTH);

        assertRefused(staticSource, "{\"BasicHttpPassword\":\"second-pw\"}",
                "Credential must set StaticTokenSecretValue, because the authentication type of this EventSource is "
                        + "StaticTokenAuthConfiguration");
        assertRefused(basicSource, "{\"HmacSecretValue\":\"0123456789abcdef\"}",
                "Credential must set BasicHttpPassword, because the authentication type of this EventSource is "
                        + "BasicHttpAuthConfiguration");
        assertRefused(hmacSource, "{\"StaticTokenSecretValue\":\"second-token\"}",
                "Credential must set HmacSecretValue, because the authentication type of this EventSource is "
                        + "HmacAuthConfiguration");
    }

    // ──────────────────────────── Order of checks ────────────────────────────

    @Test
    void constraintsComeBeforeTheRegionCheck() {
        AwsException e = assertThrows(AwsException.class, () -> service.rotateWebhookCredentials(json(
                "{\"EventSourceArn\":\"arn:aws:events:eu-west-1:000000000000:event-sourcev2/webhook/x/"
                        + "abcdefghijklmnopqrstuvwxy\",\"ClientToken\":\"a b\"}"), REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertTrue(e.getMessage().startsWith("1 validation error detected: Value 'a b' at 'clientToken'"),
                e.getMessage());
    }

    @Test
    void theRegionCheckComesBeforeTheAccountCheck() {
        assertAnswer(400, "InvalidInputException", "The region or partition in EventSourceArn does not match this "
                + "endpoint", "{\"EventSourceArn\":\"arn:aws:events:eu-west-1:111111111111:event-sourcev2/webhook/x/"
                + "abcdefghijklmnopqrstuvwxy\",\"ClientToken\":\"t\"}");
    }

    @Test
    void theAccountCheckComesBeforeTheClientTokenCheck() {
        assertAnswer(403, "AccessDeniedException", "Access denied.",
                "{\"EventSourceArn\":\"arn:aws:events:us-east-1:111111111111:event-sourcev2/webhook/x/"
                        + "abcdefghijklmnopqrstuvwxy\",\"Credential\":{}}");
    }

    @Test
    void theClientTokenCheckComesBeforeTheUnionCount() {
        assertAnswer(400, "InvalidInputException", "ClientToken is required",
                "{\"EventSourceArn\":\"" + MISSING_SOURCE + "\",\"Credential\":{}}");
        assertAnswer(400, "InvalidInputException", "ClientToken is required",
                "{\"EventSourceArn\":\"" + MISSING_SOURCE + "\",\"Credential\":{\"HmacSecretValue\":\"x12345\"}}");
    }

    @Test
    void theUnionCountComesBeforeTheSourceLookup() {
        assertAnswer(400, "InvalidInputException", UNION_MESSAGE,
                "{\"EventSourceArn\":\"" + MISSING_SOURCE + "\",\"ClientToken\":\"t\",\"Credential\":{}}");
    }

    @Test
    void theSourceLookupComesBeforeTheMemberCheck() {
        assertAnswer(404, "ResourceNotFoundException", "EventSource not found: " + MISSING_SOURCE,
                "{\"EventSourceArn\":\"" + MISSING_SOURCE + "\",\"ClientToken\":\"t\","
                        + "\"Credential\":{\"HmacSecretValue\":\"0123456789abcdef\"}}");
    }

    @Test
    void theMemberCheckComesBeforeTheValueRules() {
        EventSourceV2 source = source("hook", HMAC_AUTH);

        assertRefused(source, "{\"StaticTokenSecretValue\":\"short1\"}", "Credential must set HmacSecretValue, "
                + "because the authentication type of this EventSource is HmacAuthConfiguration");
    }

    @Test
    void theValueRulesComeBeforeTheClientTokenReplay() {
        EventSourceV2 source = source("hook", HMAC_AUTH);
        rotate(source, "t-replay", "{\"HmacSecretValue\":\"aaaaaaaaaaaaaaaa\"}");

        AwsException e = assertThrows(AwsException.class,
                () -> rotate(source, "t-replay", "{\"HmacSecretValue\":\"aaaaaaa\"}"));

        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals(HMAC_RANGE_MESSAGE, e.getMessage());
    }

    @Test
    void aRefusedRotationChangesNothing() {
        EventSourceV2 source = source("hook", HMAC_AUTH);
        Map<String, List<String>> before = secrets.listSecretVersionIds(source.getSecretArn(), REGION);

        assertRefused(source, "{\"HmacSecretValue\":\"short\"}", "1 validation error detected: Value at "
                + "'credential.hmacSecretValue' failed to satisfy constraint: Member must have length greater than "
                + "or equal to 6");
        assertRefused(source, "{\"HmacSecretValue\":\"aaaaaaa\"}", HMAC_RANGE_MESSAGE);

        assertEquals(before, secrets.listSecretVersionIds(source.getSecretArn(), REGION));
        assertEquals(0, service.describeEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\"}"), REGION)
                .getRotationRequests().size());
    }

    // ──────────────────────────── ClientToken ────────────────────────────

    @Test
    void aReplayWithTheSameRequestAnswersTheArnAndWritesNothing() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        rotate(source, "t-one", "{\"StaticTokenSecretValue\":\"second-token\"}");
        Map<String, List<String>> before = secrets.listSecretVersionIds(source.getSecretArn(), REGION);

        String again = rotate(source, "t-one", "{\"StaticTokenSecretValue\":\"second-token\"}");

        assertEquals(source.getSecretArn(), again);
        assertEquals(before, secrets.listSecretVersionIds(source.getSecretArn(), REGION));
    }

    @Test
    void aReplayStaysAReplayAfterLaterRotations() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        rotate(source, "t-one", "{\"StaticTokenSecretValue\":\"second-token\"}");
        rotate(source, "t-two", "{\"StaticTokenSecretValue\":\"third-token\"}");

        rotate(source, "t-one", "{\"StaticTokenSecretValue\":\"second-token\"}");

        assertEquals("third-token", valueOf(source, "AWSCURRENT"));
        assertEquals("second-token", valueOf(source, "AWSPREVIOUS"));
    }

    @Test
    void aReplayOfAGeneratedRotationKeepsTheGeneratedValue() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        rotate(source, "t-gen", null);
        String generated = valueOf(source, "AWSCURRENT");
        Map<String, List<String>> before = secrets.listSecretVersionIds(source.getSecretArn(), REGION);

        rotate(source, "t-gen", null);

        assertEquals(generated, valueOf(source, "AWSCURRENT"));
        assertEquals(before, secrets.listSecretVersionIds(source.getSecretArn(), REGION));
    }

    @Test
    void aReplayWithAnotherRequestIsAConflict() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        rotate(source, "t-one", "{\"StaticTokenSecretValue\":\"second-token\"}");
        rotate(source, "t-gen", null);

        for (String credential : new String[] {"{\"StaticTokenSecretValue\":\"other-token\"}", null}) {
            AwsException e = assertThrows(AwsException.class, () -> rotate(source, "t-one", credential));
            assertEquals("IdempotentParameterMismatchException", e.getErrorCode());
            assertEquals(409, e.getHttpStatus());
            assertEquals("ClientToken was used by a previous request with different parameters", e.getMessage());
        }
        AwsException e = assertThrows(AwsException.class,
                () -> rotate(source, "t-gen", "{\"StaticTokenSecretValue\":\"other-token\"}"));
        assertEquals("IdempotentParameterMismatchException", e.getErrorCode());
    }

    @Test
    void aTokenOfAnotherSourceRotatesThisOne() {
        EventSourceV2 one = source("one", STATIC_AUTH);
        EventSourceV2 two = source("two", STATIC_AUTH);
        rotate(one, "t-shared", "{\"StaticTokenSecretValue\":\"second-token\"}");

        rotate(two, "t-shared", "{\"StaticTokenSecretValue\":\"second-token\"}");

        assertEquals("second-token", valueOf(two, "AWSCURRENT"));
        assertEquals("original-token", valueOf(two, "AWSPREVIOUS"));
    }

    @Test
    void aRotationToTheCurrentValueStillRemembersItsToken() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        rotate(source, "t-same", "{\"StaticTokenSecretValue\":\"original-token\"}");

        AwsException e = assertThrows(AwsException.class,
                () -> rotate(source, "t-same", "{\"StaticTokenSecretValue\":\"other-token\"}"));

        assertEquals("IdempotentParameterMismatchException", e.getErrorCode());
    }

    @Test
    void aTokenIsRememberedForJustUnderEightHours() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        rotate(source, "t-1", "{\"StaticTokenSecretValue\":\"second-token\"}");
        clock.advance(Duration.ofHours(8).minusSeconds(1));

        assertEquals(source.getSecretArn(), rotate(source, "t-1", "{\"StaticTokenSecretValue\":\"second-token\"}"));
        AwsException e = assertThrows(AwsException.class,
                () -> rotate(source, "t-1", "{\"StaticTokenSecretValue\":\"other-token\"}"));

        assertEquals("IdempotentParameterMismatchException", e.getErrorCode());
        assertEquals("second-token", valueOf(source, "AWSCURRENT"));
    }

    @Test
    void aTokenIsForgottenAtExactlyEightHoursAndTheSameRequestRotatesAgain() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        rotate(source, "t-1", null);
        String first = versionOf(source, "AWSCURRENT");
        clock.advance(Duration.ofHours(8));

        rotate(source, "t-1", null);

        assertNotEquals(first, versionOf(source, "AWSCURRENT"));
        assertEquals(first, versionOf(source, "AWSPREVIOUS"));
        assertEquals(clock.instant(), storedRotations(source).get("t-1").rotatedAt());
    }

    @Test
    void aForgottenTokenRotatesAgainWithADifferentRequest() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        rotate(source, "t-1", "{\"StaticTokenSecretValue\":\"second-token\"}");
        clock.advance(Duration.ofHours(8));

        rotate(source, "t-1", "{\"StaticTokenSecretValue\":\"other-token\"}");

        assertEquals("other-token", valueOf(source, "AWSCURRENT"));
        assertEquals("second-token", valueOf(source, "AWSPREVIOUS"));
    }

    @Test
    void aRotationDropsTheTokensItForgot() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        rotate(source, "t-old", "{\"StaticTokenSecretValue\":\"second-token\"}");
        clock.advance(Duration.ofHours(4));
        rotate(source, "t-mid", "{\"StaticTokenSecretValue\":\"third-token\"}");
        clock.advance(Duration.ofHours(4));

        rotate(source, "t-new", "{\"StaticTokenSecretValue\":\"fourth-token\"}");

        assertEquals(Set.of("t-mid", "t-new"), storedRotations(source).keySet());
    }

    // ──────────────────────────── Secret state ────────────────────────────

    private static final String SECRET_GONE_MESSAGE =
            "The secret of the EventSource is scheduled for deletion or has been deleted";

    @Test
    void aSecretScheduledForDeletionAnswersInvalidState() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        secrets.deleteSecret(source.getSecretArn(), null, false, REGION);

        AwsException e = assertThrows(AwsException.class,
                () -> rotate(source, "{\"StaticTokenSecretValue\":\"second-token\"}"));

        assertEquals("InvalidStateException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals(SECRET_GONE_MESSAGE, e.getMessage());
        assertEquals(0, storedRotations(source).size());
    }

    @Test
    void aDeletedSecretAnswersInvalidState() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        secrets.deleteSecret(source.getSecretArn(), null, true, REGION);

        AwsException e = assertThrows(AwsException.class,
                () -> rotate(source, "{\"StaticTokenSecretValue\":\"second-token\"}"));

        assertEquals("InvalidStateException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals(SECRET_GONE_MESSAGE, e.getMessage());
    }

    @Test
    void aRememberedTokenStillReplaysWhenTheSecretIsScheduledForDeletion() {
        EventSourceV2 source = source("hook", STATIC_AUTH);
        rotate(source, "t-1", "{\"StaticTokenSecretValue\":\"second-token\"}");
        secrets.deleteSecret(source.getSecretArn(), null, false, REGION);

        assertEquals(source.getSecretArn(), rotate(source, "t-1", "{\"StaticTokenSecretValue\":\"second-token\"}"));
    }

    // ──────────────────────────── Credentials the rotation replaced ────────────────────────────

    @Test
    void aReplacedStaticTokenIsAcceptedUntilFifteenMinutesAfterTheRotation() {
        Target target = target(source("hook", STATIC_AUTH));
        assertEquals(EventBridgeV2Webhooks.OK, post(target, Map.of("x-tok", "original-token"), "{}"));

        rotate(target.source(), "{\"StaticTokenSecretValue\":\"second-token\"}");
        stampCurrentVersion(target.source());

        assertEquals(EventBridgeV2Webhooks.OK, post(target, Map.of("x-tok", "second-token"), "{}"));
        assertEquals(EventBridgeV2Webhooks.OK, post(target, Map.of("x-tok", "original-token"), "{}"));
        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertEquals(EventBridgeV2Webhooks.OK, post(target, Map.of("x-tok", "original-token"), "{}"));
        clock.advance(Duration.ofSeconds(1));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, post(target, Map.of("x-tok", "original-token"), "{}"));
        assertEquals(EventBridgeV2Webhooks.OK, post(target, Map.of("x-tok", "second-token"), "{}"));
    }

    @Test
    void aReplacedBasicCredentialIsAcceptedUntilFifteenMinutesAfterTheRotation() {
        Target target = target(source("hook", BASIC_AUTH));
        rotate(target.source(), "{\"BasicHttpPassword\":\"second-pw\"}");
        stampCurrentVersion(target.source());

        assertEquals(EventBridgeV2Webhooks.OK, post(target, Map.of("authorization", basic("alice", "second-pw")), "{}"));
        assertEquals(EventBridgeV2Webhooks.OK,
                post(target, Map.of("authorization", basic("alice", "original-pw")), "{}"));
        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertEquals(EventBridgeV2Webhooks.OK,
                post(target, Map.of("authorization", basic("alice", "original-pw")), "{}"));
        clock.advance(Duration.ofSeconds(1));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN,
                post(target, Map.of("authorization", basic("alice", "original-pw")), "{}"));
        assertEquals(EventBridgeV2Webhooks.OK, post(target, Map.of("authorization", basic("alice", "second-pw")), "{}"));
    }

    @Test
    void aReplacedHmacSecretIsAcceptedUntilFifteenMinutesAfterTheRotation() throws Exception {
        Target target = target(source("hook", HMAC_AUTH));
        rotate(target.source(), "{\"HmacSecretValue\":\"fedcba9876543210\"}");
        stampCurrentVersion(target.source());
        String body = "{\"a\":1}";
        Map<String, String> replaced = Map.of("x-sig", sign("0123456789abcdef", body));
        Map<String, String> current = Map.of("x-sig", sign("fedcba9876543210", body));

        assertEquals(EventBridgeV2Webhooks.OK, post(target, current, body));
        assertEquals(EventBridgeV2Webhooks.OK, post(target, replaced, body));
        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertEquals(EventBridgeV2Webhooks.OK, post(target, replaced, body));
        clock.advance(Duration.ofSeconds(1));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, post(target, replaced, body));
        assertEquals(EventBridgeV2Webhooks.OK, post(target, current, body));
    }

    @Test
    void onlyTheCredentialTheLastRotationReplacedIsAccepted() {
        Target target = target(source("hook", STATIC_AUTH));
        rotate(target.source(), "{\"StaticTokenSecretValue\":\"second-token\"}");
        rotate(target.source(), "{\"StaticTokenSecretValue\":\"third-token\"}");
        stampCurrentVersion(target.source());

        assertEquals(EventBridgeV2Webhooks.OK, post(target, Map.of("x-tok", "third-token"), "{}"));
        assertEquals(EventBridgeV2Webhooks.OK, post(target, Map.of("x-tok", "second-token"), "{}"));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, post(target, Map.of("x-tok", "original-token"), "{}"));
    }

    @Test
    void aSourceThatWasNeverRotatedAcceptsNothingButItsCurrentCredential() {
        Target target = target(source("hook", STATIC_AUTH));

        assertEquals(EventBridgeV2Webhooks.OK, post(target, Map.of("x-tok", "original-token"), "{}"));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, post(target, Map.of("x-tok", "other-token"), "{}"));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, post(target, Map.of(), "{}"));
    }

    @Test
    void aRotationToTheCurrentValueDoesNotStartAGracePeriodForAnythingElse() {
        Target target = target(source("hook", STATIC_AUTH));
        rotate(target.source(), "{\"StaticTokenSecretValue\":\"original-token\"}");

        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, post(target, Map.of("x-tok", "other-token"), "{}"));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private Map<String, RotationRequest> storedRotations(EventSourceV2 source) {
        return service.describeEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\"}"), REGION)
                .getRotationRequests();
    }

    private record Target(EventSourceV2 source, String hostId, String path) {
    }

    private Target target(EventSourceV2 source) {
        return new Target(source, source.getWebhookHostId(), URI.create(service.webhookUrl(source)).getPath());
    }

    private Answer post(Target target, Map<String, String> headers, String body) {
        return webhooks.receive(new Request("POST", target.hostId(), target.path(), headers,
                body.getBytes(StandardCharsets.UTF_8), false));
    }

    /** The secret stamps a new version with the real time, so the test sets the rotation time on the test clock. */
    private void stampCurrentVersion(EventSourceV2 source) {
        secrets.getSecretValue(source.getSecretArn(), null, "AWSCURRENT", REGION).setCreatedDate(clock.instant());
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private EventSourceV2 source(String name, String auth) {
        return service.createEventSource(json("{\"ClientToken\":\"" + name + "-token\",\"Name\":\"" + name
                + "\",\"EventBusArn\":\"" + bus + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                + "{\"AuthConfiguration\":" + auth + ",\"RoleArn\":\"" + ROLE + "\"}}}"), REGION);
    }

    private String rotate(EventSourceV2 source, String credential) {
        return rotate(source, "token-" + tokens++, credential);
    }

    private String rotate(EventSourceV2 source, String clientToken, String credential) {
        return service.rotateWebhookCredentials(json("{\"EventSourceArn\":\"" + source.getArn()
                + "\",\"ClientToken\":\"" + clientToken + "\""
                + (credential == null ? "" : ",\"Credential\":" + credential) + "}"), REGION);
    }

    private void assertRefused(EventSourceV2 source, String credential, String message) {
        AwsException e = assertThrows(AwsException.class, () -> rotate(source, credential));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals(message, e.getMessage());
    }

    private void assertAnswer(int status, String code, String message, String request) {
        AwsException e = assertThrows(AwsException.class, () -> service.rotateWebhookCredentials(json(request), REGION));
        assertEquals(status, e.getHttpStatus());
        assertEquals(code, e.getErrorCode());
        assertEquals(message, e.getMessage());
    }

    private String valueOf(EventSourceV2 source, String stage) {
        return secrets.getSecretValue(source.getSecretArn(), null, stage, REGION).getSecretString();
    }

    private String versionOf(EventSourceV2 source, String stage) {
        return secrets.getSecretValue(source.getSecretArn(), null, stage, REGION).getVersionId();
    }

    /** The version id of each stage, for the versions that have one. */
    private Map<String, String> stages(EventSourceV2 source) {
        Map<String, String> found = new TreeMap<>();
        secrets.listSecretVersionIds(source.getSecretArn(), REGION)
                .forEach((versionId, stages) -> stages.forEach(stage -> found.put(stage, versionId)));
        return found;
    }

    private static JsonNode json(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }
}
