package io.floci.aws.services.bedrock;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.FoundationModelDetails;
import software.amazon.awssdk.services.bedrock.model.FoundationModelSummary;
import software.amazon.awssdk.services.bedrock.model.ValidationException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class BedrockInferenceProfileMetadataIntegrationTest {

    private static final String MODEL = "amazon.nova-lite-v1:0";

    @Test
    void getFoundationModelAdvertisesOnDemandAndInferenceProfileSupport() {
        try (BedrockClient client = client(Region.US_EAST_1)) {
            FoundationModelDetails model = client.getFoundationModel(request -> request.modelIdentifier(MODEL))
                    .modelDetails();
            assertEquals(MODEL, model.modelId());
            assertEquals(List.of("ON_DEMAND", "INFERENCE_PROFILE"), model.inferenceTypesSupportedAsStrings());
        }
    }

    @Test
    void modelWithoutAProfileOnlyAdvertisesOnDemandSupport() {
        try (BedrockClient client = client(Region.US_EAST_1)) {
            String modelId = "amazon.titan-embed-text-v2:0";
            assertEquals(List.of("ON_DEMAND"), client.getFoundationModel(request -> request
                    .modelIdentifier(modelId)).modelDetails().inferenceTypesSupportedAsStrings());
            FoundationModelSummary listed = summary(client, modelId);
            assertEquals(List.of("ON_DEMAND"), listed.inferenceTypesSupportedAsStrings());
        }
    }

    @Test
    void listFoundationModelsAdvertisesOnDemandAndInferenceProfileSupport() {
        try (BedrockClient client = client(Region.US_EAST_1)) {
            FoundationModelSummary model = summary(client, MODEL);
            assertEquals(List.of("ON_DEMAND", "INFERENCE_PROFILE"), model.inferenceTypesSupportedAsStrings());
        }
    }

    @Test
    void profileOnlyRegionAdvertisesOnlyInferenceProfilesOnGetAndList() {
        String modelId = "amazon.nova-micro-v1:0";
        try (BedrockClient client = client(Region.EU_CENTRAL_1)) {
            assertEquals(List.of("INFERENCE_PROFILE"), client.getFoundationModel(request -> request
                    .modelIdentifier(modelId)).modelDetails().inferenceTypesSupportedAsStrings());
            FoundationModelSummary listed = summary(client, modelId);
            assertEquals(List.of("INFERENCE_PROFILE"), listed.inferenceTypesSupportedAsStrings());
        }
    }

    @Test
    void unavailableRegionExcludesTheModelAndRetainsGetValidation() {
        try (BedrockClient client = client(Region.AP_NORTHEAST_3)) {
            ValidationException error = assertThrows(ValidationException.class,
                    () -> client.getFoundationModel(request -> request.modelIdentifier(MODEL)));
            assertEquals(400, error.statusCode());
            assertFalse(client.listFoundationModels(request -> { }).modelSummaries().stream()
                    .anyMatch(model -> MODEL.equals(model.modelId())));
        }
    }

    private static BedrockClient client(Region region) {
        return AwsTestClients.create(BedrockClient.builder(), "741623800022", region);
    }

    private static FoundationModelSummary summary(BedrockClient client, String modelId) {
        return client.listFoundationModels(request -> { }).modelSummaries().stream()
                .filter(model -> modelId.equals(model.modelId())).findFirst().orElseThrow();
    }
}
