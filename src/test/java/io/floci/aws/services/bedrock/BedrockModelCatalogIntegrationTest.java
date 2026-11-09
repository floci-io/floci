package io.floci.aws.services.bedrock;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrock.model.FoundationModelDetails;
import software.amazon.awssdk.services.bedrock.model.FoundationModelSummary;
import software.amazon.awssdk.services.bedrock.model.GetInferenceProfileResponse;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileModel;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileStatus;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileSummary;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileType;
import software.amazon.awssdk.services.bedrock.model.InferenceType;
import software.amazon.awssdk.services.bedrock.model.ListInferenceProfilesResponse;
import software.amazon.awssdk.services.bedrock.model.ModelModality;
import software.amazon.awssdk.services.bedrock.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrock.model.ValidationException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BedrockModelCatalogIntegrationTest {

    private static final String ACCOUNT = "741623800003";
    private static final String NOVA_MICRO = "amazon.nova-micro-v1:0";
    private static final String NOVA_LITE = "amazon.nova-lite-v1:0";
    private static final String TITAN_EMBED = "amazon.titan-embed-text-v2:0";
    private static final String LLAMA = "meta.llama3-1-8b-instruct-v1:0";

    @Test
    void listFoundationModelsFiltersByProviderAndOutputModality() {
        try (BedrockClient client = client("us-east-1")) {
            List<FoundationModelSummary> models = client.listFoundationModels(request -> request
                    .byProvider("Amazon").byOutputModality(ModelModality.EMBEDDING)).modelSummaries();

            assertEquals(List.of(TITAN_EMBED), models.stream().map(FoundationModelSummary::modelId).toList());
            FoundationModelSummary titan = models.getFirst();
            assertEquals("arn:aws:bedrock:us-east-1::foundation-model/" + TITAN_EMBED, titan.modelArn());
            assertEquals("Amazon", titan.providerName());
            assertEquals(List.of(ModelModality.TEXT), titan.inputModalities());
            assertFalse(titan.responseStreamingSupported());
        }
    }

    @Test
    void listFoundationModelsOnlyListsModelsServedInTheRegion() {
        try (BedrockClient us = client("us-east-1"); BedrockClient eu = client("eu-west-1")) {
            assertTrue(modelIds(us).contains(LLAMA));
            List<String> euModels = modelIds(eu);
            assertFalse(euModels.contains(LLAMA), "Llama 3.1 8B is served from US Regions only");
            assertTrue(euModels.contains(NOVA_MICRO));
        }
    }

    @Test
    void getFoundationModelAcceptsTheIdAndTheArn() {
        try (BedrockClient client = client("eu-west-1")) {
            FoundationModelDetails byId = client.getFoundationModel(request -> request.modelIdentifier(NOVA_MICRO))
                    .modelDetails();
            FoundationModelDetails byArn = client.getFoundationModel(request -> request
                    .modelIdentifier("arn:aws:bedrock:eu-west-1::foundation-model/" + NOVA_MICRO)).modelDetails();

            assertEquals(byId, byArn);
            assertEquals("Nova Micro", byId.modelName());
            assertEquals("arn:aws:bedrock:eu-west-1::foundation-model/" + NOVA_MICRO, byId.modelArn());
        }
    }

    @Test
    void getFoundationModelRefusesAnUnknownModelAsInvalid() {
        try (BedrockClient client = client("eu-west-1")) {
            ValidationException unknown = assertThrows(ValidationException.class,
                    () -> client.getFoundationModel(request -> request.modelIdentifier("amazon.no-such-model-v1:0")));
            assertEquals(400, unknown.statusCode());
        }
    }

    @Test
    void getFoundationModelRefusesAKnownModelTheRegionDoesNotServe() {
        try (BedrockClient us = client("us-east-1"); BedrockClient usWest = client("us-west-1");
             BedrockClient osaka = client("ap-northeast-3"); BedrockClient eu = client("eu-west-1")) {
            assertEquals(NOVA_LITE, us.getFoundationModel(request -> request.modelIdentifier(NOVA_LITE))
                    .modelDetails().modelId());
            assertEquals(NOVA_LITE, usWest.getFoundationModel(request -> request.modelIdentifier(NOVA_LITE))
                    .modelDetails().modelId());

            ValidationException unserved = assertThrows(ValidationException.class,
                    () -> osaka.getFoundationModel(request -> request.modelIdentifier(NOVA_LITE)));
            assertEquals(400, unserved.statusCode());
            assertThrows(ValidationException.class, () -> eu.getFoundationModel(request -> request.modelIdentifier(LLAMA)));
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,SPEECH", "us-east-1,VIDEO", "ap-northeast-3,SPEECH", "ap-northeast-3,VIDEO"})
    void listFoundationModelsAcceptsAnOutputModalityNoCatalogModelProduces(String region, String modality) {
        try (BedrockClient client = client(region)) {
            assertTrue(client.listFoundationModels(request -> request.byOutputModality(modality))
                    .modelSummaries().isEmpty());
        }
    }

    @Test
    void listFoundationModelsRefusesAnOutputModalityOutsideTheEnum() {
        try (BedrockClient client = client("us-east-1")) {
            ValidationException error = assertThrows(ValidationException.class,
                    () -> client.listFoundationModels(request -> request.byOutputModality("AUDIO")));
            assertEquals("1 validation error detected: Value 'AUDIO' at 'byOutputModality' failed to satisfy "
                            + "constraint: Member must satisfy enum value set: [TEXT, IMAGE, EMBEDDING, SPEECH, VIDEO]",
                    error.awsErrorDetails().errorMessage());
        }
    }

    @Test
    void aModelServedOnlyThroughAProfileIsNotListedAsOnDemand() {
        try (BedrockClient client = client("eu-central-1")) {
            FoundationModelSummary novaMicro = client.listFoundationModels(request -> { }).modelSummaries().stream()
                    .filter(model -> model.modelId().equals(NOVA_MICRO)).findFirst().orElseThrow();
            assertTrue(novaMicro.inferenceTypesSupported().isEmpty(),
                    "Nova Micro is reachable from eu-central-1 only through eu." + NOVA_MICRO);

            List<String> onDemand = client.listFoundationModels(request -> request
                    .byInferenceType(InferenceType.ON_DEMAND)).modelSummaries().stream()
                    .map(FoundationModelSummary::modelId).toList();
            assertFalse(onDemand.contains(NOVA_MICRO));
            assertTrue(onDemand.contains(TITAN_EMBED));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "arn:aws:bedrock-runtime:eu-west-1::foundation-model/" + NOVA_MICRO,
        "arn:aws:bedrock:us-east-1::foundation-model/" + NOVA_MICRO,
        "arn:aws-cn:bedrock:eu-west-1::foundation-model/" + NOVA_MICRO,
        "arn:aws:bedrock:eu-west-1:" + ACCOUNT + ":foundation-model/" + NOVA_MICRO,
        "arn:aws:bedrock:eu-west-1::custom-model/" + NOVA_MICRO})
    void getFoundationModelRefusesAnArnThatDoesNotNameTheModelInTheRegion(String arn) {
        try (BedrockClient client = client("eu-west-1")) {
            ValidationException error = assertThrows(ValidationException.class,
                    () -> client.getFoundationModel(request -> request.modelIdentifier(arn)));
            assertEquals(400, error.statusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "arn:aws:bedrock:us-east-1:" + ACCOUNT + ":inference-profile/us.amazon.nova-lite-v1:0",
        "arn:aws-cn:bedrock:us-west-1:" + ACCOUNT + ":inference-profile/us.amazon.nova-lite-v1:0",
        "arn:aws:bedrock:us-west-1:" + ACCOUNT + ":application-inference-profile/us.amazon.nova-lite-v1:0"})
    void getInferenceProfileRefusesAnArnThatDoesNotNameTheProfileInTheRegion(String arn) {
        try (BedrockClient client = client("us-west-1")) {
            ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class,
                    () -> client.getInferenceProfile(request -> request.inferenceProfileIdentifier(arn)));
            assertEquals(404, error.statusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "arn:aws:bedrock-runtime:us-west-1:" + ACCOUNT + ":inference-profile/us.amazon.nova-lite-v1:0",
        "arn:aws:bedrock:us-west-1::inference-profile/us.amazon.nova-lite-v1:0"})
    void getInferenceProfileRefusesAnArnOfAnotherServiceOrWithoutAnAccountAsInvalid(String arn) {
        try (BedrockClient client = client("us-west-1")) {
            ValidationException error = assertThrows(ValidationException.class,
                    () -> client.getInferenceProfile(request -> request.inferenceProfileIdentifier(arn)));
            assertEquals(400, error.statusCode());
        }
    }

    @Test
    void getInferenceProfileRefusesAnArnOfAnotherAccountAsAccessDenied() {
        try (BedrockClient client = client("us-west-1")) {
            AccessDeniedException error = assertThrows(AccessDeniedException.class,
                    () -> client.getInferenceProfile(request -> request
                            .inferenceProfileIdentifier("arn:aws:bedrock:us-west-1:741623800004:inference-profile/"
                                    + "us.amazon.nova-lite-v1:0")));
            assertEquals(403, error.statusCode());
        }
    }

    @Test
    void listInferenceProfilesReturnsTheProfilesTheSourceRegionCanUse() {
        try (BedrockClient us = client("us-east-1"); BedrockClient eu = client("eu-west-1")) {
            List<String> usProfiles = profileIds(us);
            assertTrue(usProfiles.contains("us." + NOVA_MICRO));
            assertFalse(usProfiles.contains("eu." + NOVA_MICRO));

            List<String> euProfiles = profileIds(eu);
            assertTrue(euProfiles.contains("eu." + NOVA_MICRO));
            assertFalse(euProfiles.contains("us." + NOVA_MICRO));
        }
    }

    @Test
    void getInferenceProfileListsTheDestinationRegionsOfTheSourceRegion() {
        try (BedrockClient client = client("us-west-1")) {
            GetInferenceProfileResponse profile = client.getInferenceProfile(request -> request
                    .inferenceProfileIdentifier("us.amazon.nova-lite-v1:0"));

            assertEquals("arn:aws:bedrock:us-west-1:" + ACCOUNT + ":inference-profile/us.amazon.nova-lite-v1:0",
                    profile.inferenceProfileArn());
            assertEquals(InferenceProfileType.SYSTEM_DEFINED, profile.type());
            assertEquals(InferenceProfileStatus.ACTIVE, profile.status());
            assertEquals(List.of(
                    "arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-lite-v1:0",
                    "arn:aws:bedrock:us-east-2::foundation-model/amazon.nova-lite-v1:0",
                    "arn:aws:bedrock:us-west-1::foundation-model/amazon.nova-lite-v1:0",
                    "arn:aws:bedrock:us-west-2::foundation-model/amazon.nova-lite-v1:0"),
                    profile.models().stream().map(InferenceProfileModel::modelArn).toList());

            GetInferenceProfileResponse byArn = client.getInferenceProfile(request -> request
                    .inferenceProfileIdentifier(profile.inferenceProfileArn()));
            assertEquals(profile.inferenceProfileId(), byArn.inferenceProfileId());
        }
    }

    @Test
    void getInferenceProfileRefusesAProfileTheSourceRegionCannotUse() {
        try (BedrockClient client = client("us-east-1")) {
            ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class,
                    () -> client.getInferenceProfile(request -> request.inferenceProfileIdentifier("eu." + NOVA_MICRO)));
            assertEquals(404, error.statusCode());
        }
    }

    @Test
    void listInferenceProfilesPagesThroughEveryProfile() {
        try (BedrockClient client = client("us-east-1")) {
            List<String> all = profileIds(client);
            List<String> paged = new ArrayList<>();
            String token = null;
            do {
                String current = token;
                ListInferenceProfilesResponse page = client.listInferenceProfiles(request -> request
                        .maxResults(1).nextToken(current));
                assertTrue(page.inferenceProfileSummaries().size() <= 1);
                page.inferenceProfileSummaries().forEach(summary -> paged.add(summary.inferenceProfileId()));
                token = page.nextToken();
            } while (token != null);

            assertTrue(all.size() > 1);
            assertEquals(all, paged);
        }
    }

    @Test
    void listInferenceProfilesFiltersByType() {
        try (BedrockClient client = client("us-east-1")) {
            ListInferenceProfilesResponse application = client.listInferenceProfiles(request -> request
                    .typeEquals(InferenceProfileType.APPLICATION));
            assertTrue(application.inferenceProfileSummaries().isEmpty());
            assertNull(application.nextToken());

            assertTrue(client.listInferenceProfiles(request -> request.typeEquals(InferenceProfileType.SYSTEM_DEFINED))
                    .inferenceProfileSummaries().stream()
                    .allMatch(summary -> summary.type() == InferenceProfileType.SYSTEM_DEFINED));
        }
    }

    @Test
    void listInferenceProfilesRefusesAnOutOfRangePageSize() {
        try (BedrockClient client = client("us-east-1")) {
            assertThrows(ValidationException.class, () -> client.listInferenceProfiles(request -> request.maxResults(0)));
            assertThrows(ValidationException.class, () -> client.listInferenceProfiles(request -> request.maxResults(1001)));
        }
    }

    private static List<String> modelIds(BedrockClient client) {
        return client.listFoundationModels(request -> { }).modelSummaries().stream()
                .map(FoundationModelSummary::modelId).toList();
    }

    private static List<String> profileIds(BedrockClient client) {
        return client.listInferenceProfiles(request -> request.maxResults(1000)).inferenceProfileSummaries().stream()
                .map(InferenceProfileSummary::inferenceProfileId).toList();
    }

    private static BedrockClient client(String region) {
        return BedrockClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .build();
    }
}
