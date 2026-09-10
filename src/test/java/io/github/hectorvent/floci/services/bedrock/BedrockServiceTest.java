package io.github.hectorvent.floci.services.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bedrock.model.Guardrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private BedrockService service;

    @BeforeEach
    void setUp() {
        service = new BedrockService(new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT), mapper);
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

    @Test
    void guardrailsAreScopedToTheirRegion() {
        Guardrail created = create("regional");
        assertThrows(AwsException.class,
                () -> service.getGuardrail(created.getGuardrailId(), null, "eu-west-1"));
        assertTrue(service.listGuardrails(null, null, null, "eu-west-1").items().isEmpty());
    }
}
