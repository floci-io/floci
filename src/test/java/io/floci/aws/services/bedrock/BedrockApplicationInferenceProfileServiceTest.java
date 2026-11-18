package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.bedrock.model.ApplicationInferenceProfile;
import io.floci.aws.services.kms.KmsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BedrockApplicationInferenceProfileServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String MODEL_ARN =
            "arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-micro-v1:0";
    private static final String SYSTEM_PROFILE_ARN =
            "arn:aws:bedrock:us-east-1:" + ACCOUNT + ":inference-profile/us.amazon.nova-lite-v1:0";

    private final InMemoryStorage<String, ApplicationInferenceProfile> profiles = new InMemoryStorage<>();
    private final BedrockService service = serviceFor(ACCOUNT);

    private BedrockService serviceFor(String account) {
        return new BedrockService(new InMemoryStorage<>(), new InMemoryStorage<>(), profiles,
                new RegionResolver(REGION, account), new ObjectMapper(), mock(KmsService.class));
    }

    @Test
    void applicationProfilePersistsAndIsListedAlongsideSystemProfiles() {
        ApplicationInferenceProfile created = service.createApplicationInferenceProfile(
                "my profile", "test profile", MODEL_ARN, Map.of("team", "ml"), REGION);
        BedrockService restarted = serviceFor(ACCOUNT);

        assertEquals("arn:aws:bedrock:us-east-1:" + ACCOUNT + ":application-inference-profile/"
                + created.getInferenceProfileId(), created.getInferenceProfileArn());
        assertEquals(List.of(MODEL_ARN), restarted.getApplicationInferenceProfile(
                created.getInferenceProfileArn(), REGION).getModels());
        assertEquals(created.getInferenceProfileId(), restarted.getInferenceProfile(
                created.getInferenceProfileId(), REGION).inferenceProfileId());
        assertEquals("APPLICATION", restarted.getInferenceProfile(
                created.getInferenceProfileArn(), REGION).type());
        assertEquals(List.of(created.getInferenceProfileId()), restarted.listInferenceProfiles(
                "APPLICATION", null, null, REGION).items().stream()
                .map(BedrockService.InferenceProfileView::inferenceProfileId).toList());
        assertTrue(restarted.listInferenceProfiles(null, null, null, REGION).items().stream()
                .anyMatch(profile -> "SYSTEM_DEFINED".equals(profile.type())));
        assertEquals(Map.of("team", "ml"), restarted.listTags(created.getInferenceProfileArn(), REGION));
    }

    @Test
    void systemProfileSourceCopiesEveryDestinationModelAndTagsRoundTrip() {
        ApplicationInferenceProfile created = service.createApplicationInferenceProfile(
                "cross region", null, SYSTEM_PROFILE_ARN, Map.of(), REGION);
        assertEquals(service.getInferenceProfile("us.amazon.nova-lite-v1:0", REGION).models(),
                created.getModels());

        service.tagResource(created.getInferenceProfileArn(), Map.of("owner", "team"), REGION);
        assertEquals(Map.of("owner", "team"), service.listTags(created.getInferenceProfileArn(), REGION));
        service.untagResource(created.getInferenceProfileArn(), List.of("owner"), REGION);
        assertEquals(Map.of(), service.listTags(created.getInferenceProfileArn(), REGION));
        assertTrue(service.getResources().stream()
                .anyMatch(resource -> created.getInferenceProfileArn().equals(resource.arn())));
    }

    @Test
    void deleteRemovesTheProfileAndCannotDeleteSystemProfiles() {
        ApplicationInferenceProfile created = service.createApplicationInferenceProfile(
                "to delete", null, MODEL_ARN, Map.of(), REGION);
        service.deleteApplicationInferenceProfile(created.getInferenceProfileArn(), REGION);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getInferenceProfile(created.getInferenceProfileId(), REGION)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.deleteApplicationInferenceProfile(SYSTEM_PROFILE_ARN, REGION)).getErrorCode());
    }

    @Test
    void profilesAreIsolatedByAccountAndRegion() {
        ApplicationInferenceProfile created = service.createApplicationInferenceProfile(
                "private", null, MODEL_ARN, Map.of(), REGION);
        BedrockService otherAccount = serviceFor("000000000001");

        assertTrue(otherAccount.listInferenceProfiles("APPLICATION", null, null, REGION).items().isEmpty());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> otherAccount.getInferenceProfile(created.getInferenceProfileId(), REGION)).getErrorCode());
        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> otherAccount.getInferenceProfile(created.getInferenceProfileArn(), REGION)).getErrorCode());
        assertTrue(service.listInferenceProfiles("APPLICATION", null, null, "us-west-2").items().isEmpty());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getInferenceProfile(created.getInferenceProfileArn(), "us-west-2")).getErrorCode());
    }

    @Test
    void sourceAndDuplicateNameValidationLeaveStorageUnchanged() {
        for (String invalidSource : List.of("not-an-arn",
                "arn:aws:bedrock:eu-west-1::foundation-model/amazon.nova-micro-v1:0",
                "arn:aws:bedrock:us-east-1:" + ACCOUNT + ":application-inference-profile/other")) {
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> service.createApplicationInferenceProfile(
                            "bad source", null, invalidSource, Map.of(), REGION)).getErrorCode());
        }
        assertTrue(service.listInferenceProfiles("APPLICATION", null, null, REGION).items().isEmpty());
        service.createApplicationInferenceProfile("taken", null, MODEL_ARN, Map.of(), REGION);
        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> service.createApplicationInferenceProfile("taken", null, MODEL_ARN, Map.of(), REGION))
                .getErrorCode());
    }

    @Test
    void idempotencyTokenReturnsTheOriginalProfile() {
        ApplicationInferenceProfile created = service.createApplicationInferenceProfile(
                "first", null, MODEL_ARN, Map.of(), "same-token", REGION);
        ApplicationInferenceProfile replay = service.createApplicationInferenceProfile(
                "changed", "changed", "invalid source", Map.of("other", "value"), "same-token", REGION);

        assertEquals(created.getInferenceProfileArn(), replay.getInferenceProfileArn());
        assertEquals("first", replay.getInferenceProfileName());
        assertEquals(1, service.listInferenceProfiles("APPLICATION", null, null, REGION).items().size());
        assertFalse(service.listTags(created.getInferenceProfileArn(), REGION).containsKey("other"));
    }
}
