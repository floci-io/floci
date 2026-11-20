package io.floci.aws.services.sagemaker;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;
import software.amazon.awssdk.services.sagemaker.model.ListModelsRequest;
import software.amazon.awssdk.services.sagemaker.model.ListModelsResponse;
import software.amazon.awssdk.services.sagemaker.model.ModelSummary;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code ListModels} returns only the models created inside its {@code CreationTimeAfter}/{@code Before} window. */
@QuarkusTest
class SageMakerListModelsCreationTimeIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @Test
    void creationTimeFiltersKeepOnlyTheModelsCreatedInTheirWindow() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        List<String> created = List.of("window-a-" + suffix, "window-b-" + suffix);
        try (SageMakerClient sagemaker = AwsTestClients.create(SageMakerClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                created.forEach(name -> createModel(sagemaker, name));
                Instant createdAt = sagemaker.describeModel(r -> r.modelName(created.getFirst())).creationTime();
                Instant before = createdAt.minus(Duration.ofHours(1));
                Instant after = createdAt.plus(Duration.ofHours(1));

                assertEquals(List.of(), names(sagemaker, suffix, r -> r.creationTimeBefore(Instant.EPOCH)));
                assertEquals(List.of(), names(sagemaker, suffix, r -> r.creationTimeAfter(after)));
                assertEquals(created, names(sagemaker, suffix, r -> r.creationTimeAfter(before)));
                assertEquals(created, names(sagemaker, suffix, r -> r.creationTimeBefore(after)));
                assertEquals(created, names(sagemaker, suffix,
                        r -> r.creationTimeAfter(before).creationTimeBefore(after)));
            } finally {
                created.forEach(name -> sagemaker.deleteModel(r -> r.modelName(name)));
            }
        }
    }

    /** The names of the models whose name holds {@code suffix}, by name, one page at a time. */
    private static List<String> names(SageMakerClient sagemaker, String suffix,
                                      Consumer<ListModelsRequest.Builder> filter) {
        List<String> names = new ArrayList<>();
        String token = null;
        do {
            String next = token;
            ListModelsResponse page = sagemaker.listModels(r -> {
                filter.accept(r.nameContains(suffix).sortBy("Name").sortOrder("Ascending").maxResults(1));
                r.nextToken(next);
            });
            page.models().stream().map(ModelSummary::modelName).forEach(names::add);
            token = page.nextToken();
        } while (token != null);
        return names;
    }

    private static void createModel(SageMakerClient sagemaker, String name) {
        sagemaker.createModel(r -> r.modelName(name)
                .executionRoleArn("arn:aws:iam::" + ACCOUNT + ":role/sagemaker")
                .primaryContainer(c -> c.image("123456789012.dkr.ecr.us-east-1.amazonaws.com/model:latest")));
    }
}
