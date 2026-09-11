package io.github.hectorvent.floci.services.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bedrock.model.Guardrail;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class BedrockServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String KEY_ID = "8f2b1c4e-0a7d-4e5f-9b31-6c8d2e4f7a90";
    private static final String KEY_ARN = "arn:aws:kms:" + REGION + ":" + ACCOUNT + ":key/" + KEY_ID;

    private final ObjectMapper mapper = new ObjectMapper();
    private KmsService kmsService;
    private BedrockService service;

    @BeforeEach
    void setUp() {
        kmsService = mock(KmsService.class);
        service = new BedrockService(new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT), mapper, kmsService);
    }

    /** Registers one usable KMS key that answers to every form the AWS model accepts. */
    private void knownKey(String... forms) {
        KmsKey key = new KmsKey();
        key.setKeyId(KEY_ID);
        key.setArn(KEY_ARN);
        key.setEnabled(true);
        key.setKeyState("Enabled");
        for (String form : forms) {
            doReturn(key).when(kmsService).describeKey(form, REGION);
        }
    }

    private ObjectNode createRequest(String name) {
        ObjectNode request = mapper.createObjectNode();
        request.put("name", name);
        request.put("blockedInputMessaging", "input blocked");
        request.put("blockedOutputsMessaging", "output blocked");
        return request;
    }

    private Guardrail create(String name) {
        return service.createGuardrail(createRequest(name), REGION);
    }

    @Test
    void createProducesADraftWithAModelShapedIdAndArn() {
        Guardrail guardrail = create("my-guardrail");

        assertTrue(guardrail.getGuardrailId().matches("[a-z0-9]+"), guardrail.getGuardrailId());
        assertEquals(BedrockService.DRAFT_VERSION, guardrail.getVersion());
        assertEquals("arn:aws:bedrock:" + REGION + ":" + ACCOUNT + ":guardrail/" + guardrail.getGuardrailId(),
                guardrail.getGuardrailArn());
        assertNotNull(guardrail.getCreatedAt());
        assertEquals(guardrail.getCreatedAt(), guardrail.getUpdatedAt());
    }

    @Test
    void createRenamesPolicyConfigMembersToTheirReadShapes() {
        ObjectNode request = createRequest("policies");
        request.putObject("topicPolicyConfig").putArray("topicsConfig").addObject()
                .put("name", "Investments").put("type", "DENY");
        request.putObject("contentPolicyConfig").putArray("filtersConfig").addObject()
                .put("type", "HATE").put("inputStrength", "HIGH");
        request.putObject("wordPolicyConfig").putArray("wordsConfig").addObject().put("text", "forbidden");
        request.putObject("sensitiveInformationPolicyConfig").putArray("piiEntitiesConfig").addObject()
                .put("type", "EMAIL").put("action", "BLOCK");

        Guardrail guardrail = service.createGuardrail(request, REGION);

        assertEquals("Investments", guardrail.getTopicPolicy().path("topics").path(0).path("name").asText());
        assertEquals("HATE", guardrail.getContentPolicy().path("filters").path(0).path("type").asText());
        assertEquals("forbidden", guardrail.getWordPolicy().path("words").path(0).path("text").asText());
        assertEquals("EMAIL",
                guardrail.getSensitiveInformationPolicy().path("piiEntities").path(0).path("type").asText());
    }

    @Test
    void createRejectsAnEmptyRequiredMember() {
        ObjectNode request = mapper.createObjectNode();
        request.put("name", "no-messaging");
        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createRejectsANameOutsideTheModelPattern() {
        AwsException error = assertThrows(AwsException.class, () -> create("not a valid name"));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void createRejectsADuplicateName() {
        create("duplicate");
        AwsException error = assertThrows(AwsException.class, () -> create("duplicate"));
        assertEquals("ConflictException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void getResolvesAnArnAsWellAsAnId() {
        Guardrail created = create("by-arn");
        Guardrail byArn = service.getGuardrail(created.getGuardrailArn(), null, REGION);
        assertEquals(created.getGuardrailId(), byArn.getGuardrailId());
    }

    @Test
    void getRejectsAMalformedIdentifier() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.getGuardrail("arn:aws:bedrock", null, REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void getMissingGuardrailRaisesResourceNotFound() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.getGuardrail("doesnotexist", null, REGION));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void updateWritesTheDraftAndLeavesPublishedVersionsAlone() {
        Guardrail created = create("versioned");
        service.createGuardrailVersion(created.getGuardrailId(), "first cut", REGION);

        ObjectNode update = createRequest("versioned");
        update.put("description", "after the update");
        service.updateGuardrail(created.getGuardrailId(), update, REGION);

        assertEquals("after the update",
                service.getGuardrail(created.getGuardrailId(), null, REGION).getDescription());
        assertEquals("first cut",
                service.getGuardrail(created.getGuardrailId(), "1", REGION).getDescription());
    }

    @Test
    void versionsAreNumberedFromOne() {
        Guardrail created = create("counting");
        assertEquals("1", service.createGuardrailVersion(created.getGuardrailId(), null, REGION).getVersion());
        assertEquals("2", service.createGuardrailVersion(created.getGuardrailId(), null, REGION).getVersion());
    }

    @Test
    void listWithoutAnIdentifierReturnsOneDraftPerGuardrail() {
        Guardrail first = create("first");
        create("second");
        service.createGuardrailVersion(first.getGuardrailId(), null, REGION);

        List<Guardrail> listed = service.listGuardrails(null, null, null, REGION).items();

        assertEquals(2, listed.size());
        assertTrue(listed.stream().allMatch(g -> BedrockService.DRAFT_VERSION.equals(g.getVersion())));
    }

    @Test
    void listWithAnIdentifierReturnsEveryVersion() {
        Guardrail created = create("all-versions");
        service.createGuardrailVersion(created.getGuardrailId(), null, REGION);

        List<Guardrail> listed = service.listGuardrails(created.getGuardrailId(), null, null, REGION).items();

        assertEquals(2, listed.size());
        assertEquals(List.of("1", BedrockService.DRAFT_VERSION),
                listed.stream().map(Guardrail::getVersion).toList());
    }

    @Test
    void listPagesOnMaxResultsAndResumesFromTheToken() {
        create("alpha");
        create("beta");
        create("gamma");

        PaginatedResult<Guardrail> firstPage = service.listGuardrails(null, 2, null, REGION);
        assertEquals(2, firstPage.items().size());
        assertNotNull(firstPage.nextToken());

        PaginatedResult<Guardrail> secondPage =
                service.listGuardrails(null, 2, firstPage.nextToken(), REGION);
        assertEquals(1, secondPage.items().size());
        assertNull(secondPage.nextToken());
    }

    @Test
    void listRejectsAMaxResultsOutsideTheModelRange() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.listGuardrails(null, 0, null, REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void deleteWithoutAVersionRemovesEveryVersion() {
        Guardrail created = create("gone");
        service.createGuardrailVersion(created.getGuardrailId(), null, REGION);

        service.deleteGuardrail(created.getGuardrailId(), null, REGION);

        assertThrows(AwsException.class, () -> service.getGuardrail(created.getGuardrailId(), null, REGION));
        assertThrows(AwsException.class, () -> service.getGuardrail(created.getGuardrailId(), "1", REGION));
    }

    @Test
    void deleteRejectsANonNumericalVersion() {
        Guardrail created = create("draft-delete");
        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteGuardrail(created.getGuardrailId(), BedrockService.DRAFT_VERSION, REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void tagsRoundTripThroughTheArn() {
        Guardrail created = create("tagged");
        String arn = created.getGuardrailArn();

        service.tagResource(arn, Map.of("team", "ai"), REGION);
        service.tagResource(arn, Map.of("env", "test"), REGION);
        assertEquals(Map.of("team", "ai", "env", "test"), service.listTags(arn, REGION));

        service.untagResource(arn, List.of("env"), REGION);
        assertEquals(Map.of("team", "ai"), service.listTags(arn, REGION));
    }

    @Test
    void taggingAnUnknownResourceRaisesResourceNotFound() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.listTags("arn:aws:bedrock:" + REGION + ":" + ACCOUNT + ":guardrail/missing", REGION));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    // Length constraints from the AWS model

    @Test
    void createAcceptsADescriptionExactlyAtTheModelCap() {
        ObjectNode request = createRequest("description-at-cap");
        request.put("description", "d".repeat(200));

        assertEquals(200, service.createGuardrail(request, REGION).getDescription().length());
    }

    @Test
    void createRejectsADescriptionOverTheModelCap() {
        ObjectNode request = createRequest("description-over-cap");
        request.put("description", "d".repeat(201));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(error.getMessage().contains("description"), error.getMessage());
    }

    @Test
    void createRejectsAnEmptyDescription() {
        ObjectNode request = createRequest("empty-description");
        request.put("description", "");

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void createAcceptsBlockedMessagingExactlyAtTheModelCap() {
        ObjectNode request = createRequest("messaging-at-cap");
        request.put("blockedInputMessaging", "i".repeat(500));
        request.put("blockedOutputsMessaging", "o".repeat(500));

        Guardrail guardrail = service.createGuardrail(request, REGION);

        assertEquals(500, guardrail.getBlockedInputMessaging().length());
        assertEquals(500, guardrail.getBlockedOutputsMessaging().length());
    }

    @Test
    void createRejectsBlockedInputMessagingOverTheModelCap() {
        ObjectNode request = createRequest("input-over-cap");
        request.put("blockedInputMessaging", "i".repeat(501));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().contains("blockedInputMessaging"), error.getMessage());
    }

    @Test
    void createRejectsBlockedOutputsMessagingOverTheModelCap() {
        ObjectNode request = createRequest("outputs-over-cap");
        request.put("blockedOutputsMessaging", "o".repeat(501));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().contains("blockedOutputsMessaging"), error.getMessage());
    }

    @Test
    void createAcceptsANameExactlyAtTheModelCap() {
        assertEquals(50, create("n".repeat(50)).getName().length());
    }

    @Test
    void createRejectsANameOverTheModelCap() {
        AwsException error = assertThrows(AwsException.class, () -> create("n".repeat(51)));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void updateEnforcesTheSameLengthCapsAsCreate() {
        Guardrail created = create("update-caps");

        ObjectNode request = createRequest("update-caps");
        request.put("description", "d".repeat(201));
        AwsException error = assertThrows(AwsException.class,
                () -> service.updateGuardrail(created.getGuardrailId(), request, REGION));
        assertEquals("ValidationException", error.getErrorCode());

        ObjectNode overLong = createRequest("update-caps");
        overLong.put("blockedOutputsMessaging", "o".repeat(501));
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.updateGuardrail(created.getGuardrailId(), overLong, REGION)).getErrorCode());
    }

    // kmsKeyId normalisation

    @Test
    void createResolvesABareKeyIdToTheKeyArn() {
        knownKey(KEY_ID);
        ObjectNode request = createRequest("kms-by-id");
        request.put("kmsKeyId", KEY_ID);

        assertEquals(KEY_ARN, service.createGuardrail(request, REGION).getKmsKeyArn());
    }

    @Test
    void createResolvesAnAliasToTheKeyArn() {
        knownKey("alias/guardrails");
        ObjectNode request = createRequest("kms-by-alias");
        request.put("kmsKeyId", "alias/guardrails");

        assertEquals(KEY_ARN, service.createGuardrail(request, REGION).getKmsKeyArn());
    }

    @Test
    void createKeepsAFullKeyArn() {
        knownKey(KEY_ARN);
        ObjectNode request = createRequest("kms-by-arn");
        request.put("kmsKeyId", KEY_ARN);

        assertEquals(KEY_ARN, service.createGuardrail(request, REGION).getKmsKeyArn());
    }

    @Test
    void createWithoutAKmsKeyLeavesTheArnUnset() {
        assertNull(create("kms-absent").getKmsKeyArn());
    }

    @Test
    void createRejectsAKmsKeyThatDoesNotResolve() {
        doThrow(new AwsException("NotFoundException", "Key not found: missing", 404))
                .when(kmsService).describeKey("missing", REGION);
        ObjectNode request = createRequest("kms-missing");
        request.put("kmsKeyId", "missing");

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createRejectsAKmsKeyPendingDeletion() {
        KmsKey key = new KmsKey();
        key.setKeyId(KEY_ID);
        key.setArn(KEY_ARN);
        key.setEnabled(false);
        key.setKeyState("PendingDeletion");
        doReturn(key).when(kmsService).describeKey(KEY_ID, REGION);
        ObjectNode request = createRequest("kms-pending");
        request.put("kmsKeyId", KEY_ID);

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void updateResolvesTheKeyAndTheVersionSnapshotKeepsIt() {
        knownKey(KEY_ID, "alias/guardrails");
        Guardrail created = create("kms-on-update");

        ObjectNode request = createRequest("kms-on-update");
        request.put("kmsKeyId", "alias/guardrails");
        assertEquals(KEY_ARN, service.updateGuardrail(created.getGuardrailId(), request, REGION).getKmsKeyArn());

        assertEquals(KEY_ARN,
                service.createGuardrailVersion(created.getGuardrailId(), null, REGION).getKmsKeyArn());
    }

    @Test
    void guardrailsAreScopedToTheirRegion() {
        Guardrail created = create("regional");
        assertThrows(AwsException.class,
                () -> service.getGuardrail(created.getGuardrailId(), null, "eu-west-1"));
        assertTrue(service.listGuardrails(null, null, null, "eu-west-1").items().isEmpty());
    }
}
