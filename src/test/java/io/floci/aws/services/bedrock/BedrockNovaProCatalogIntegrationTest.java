package io.floci.aws.services.bedrock;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.FoundationModelDetails;
import software.amazon.awssdk.services.bedrock.model.ValidationException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BedrockNovaProCatalogIntegrationTest {

    private static final String MODEL = "amazon.nova-pro-v1:0";
    private static final String ACCOUNT = "741623800022";

    @Test
    void novaProMetadataMatchesThePublishedModelCard() {
        try (BedrockClient client = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            FoundationModelDetails model = assertDoesNotThrow(() -> client.getFoundationModel(request -> request
                    .modelIdentifier(MODEL))).modelDetails();
            assertEquals("Nova Pro", model.modelName());
            assertEquals("Amazon", model.providerName());
            assertEquals(List.of("TEXT", "IMAGE", "VIDEO"), model.inputModalitiesAsStrings());
            assertEquals(List.of("TEXT"), model.outputModalitiesAsStrings());
            assertTrue(model.responseStreamingSupported());
            assertTrue(model.inferenceTypesSupportedAsStrings().contains("ON_DEMAND"));
            assertTrue(client.listFoundationModels(request -> request.byProvider("Amazon")).modelSummaries()
                    .stream().anyMatch(summary -> MODEL.equals(summary.modelId())));
        }
    }

    @Test
    void existingNovaLiteVideoMetadataRemainsAvailable() {
        try (BedrockClient client = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertEquals(List.of("TEXT", "IMAGE", "VIDEO"), client.getFoundationModel(request -> request
                    .modelIdentifier("amazon.nova-lite-v1:0")).modelDetails().inputModalitiesAsStrings());
        }
    }

    @Test
    void unknownModelStillUsesTheAcceptedValidationError() {
        try (BedrockClient client = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            ValidationException error = assertThrows(ValidationException.class, () -> client.getFoundationModel(request -> request
                    .modelIdentifier("amazon.nonexistent-model-v1:0")));
            assertEquals(400, error.statusCode());
        }
    }
    @ParameterizedTest
    @CsvSource({
        "us-east-1, aws",
        "us-gov-west-1, aws-us-gov",
        "eu-west-2, aws",
        "ap-southeast-2, aws",
        "ap-southeast-3, aws",
        "me-central-1, aws"
    })
    void novaProIsDiscoverableInEachPublishedDirectRegion(String region, String partition) {
        try (BedrockClient client = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, Region.of(region))) {
            FoundationModelDetails model = client.getFoundationModel(request -> request.modelIdentifier(MODEL))
                    .modelDetails();
            assertEquals("arn:" + partition + ":bedrock:" + region + "::foundation-model/" + MODEL, model.modelArn());
            assertEquals(List.of("ON_DEMAND"), model.inferenceTypesSupportedAsStrings());
            assertEquals("ACTIVE", model.modelLifecycle().statusAsString());
            assertTrue(client.listFoundationModels(request -> request.byProvider("Amazon").byInferenceType("ON_DEMAND"))
                    .modelSummaries().stream().anyMatch(summary -> MODEL.equals(summary.modelId())));
        }
    }

    @Test
    void novaProIsNotAdvertisedInAnUnservedPartition() {
        try (BedrockClient client = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, Region.CN_NORTH_1)) {
            assertThrows(ValidationException.class, () -> client.getFoundationModel(request -> request.modelIdentifier(MODEL)));
            assertTrue(client.listFoundationModels(request -> { }).modelSummaries().stream()
                    .noneMatch(summary -> MODEL.equals(summary.modelId())));
        }
    }


}
