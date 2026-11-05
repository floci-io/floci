package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.InvalidParameterException;
import software.amazon.awssdk.services.ecr.model.LifecyclePolicyNotFoundException;
import software.amazon.awssdk.services.ecr.model.RepositoryNotFoundException;

import java.util.UUID;
import java.util.stream.Stream;

import static io.github.hectorvent.floci.testing.CfnStackWaits.awaitStackDeleted;
import static io.github.hectorvent.floci.testing.CfnStackWaits.awaitStatus;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class EcrLifecyclePolicyValidationIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RULE = """
            {"rulePriority":1,"selection":{"tagStatus":"any","countType":"imageCountMoreThan",
            "countNumber":2},"action":{"type":"expire"}}
            """;
    private static final String VALID_POLICY = "{\"rules\":[" + RULE + "]}";
    private static final String DISTINCT_PRIORITIES = "{\"rules\":["
            + RULE.replace("\"tagStatus\":\"any\"", "\"tagStatus\":\"untagged\"") + ","
            + RULE.replace("\"rulePriority\":1", "\"rulePriority\":2") + "]}";

    @InjectMock
    EcrRegistryManager registry;

    @BeforeEach
    void keepRepositoryMetadataIndependentOfDocker() {
        when(registry.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenReturn("registry.invalid/lifecycle-validation");
    }

    @Test
    void emptyCloudFormationUpdatePreservesPolicyAndOmittedPolicyStillRemovesIt() {
        String repository = "lifecycle-validation/" + UUID.randomUUID();
        String stack = "lifecycle-validation-" + UUID.randomUUID();
        try (EcrClient client = AwsTestClients.create(EcrClient.builder(), "test", Region.US_EAST_1);
             CloudFormationClient cfn = AwsTestClients.create(
                     CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            ObjectNode template = JSON.createObjectNode();
            ObjectNode properties = template.putObject("Resources").putObject("Repo")
                    .put("Type", "AWS::ECR::Repository").putObject("Properties");
            properties.put("RepositoryName", repository);
            properties.putObject("LifecyclePolicy").put("LifecyclePolicyText", VALID_POLICY);
            cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
            try {
                awaitStatus(cfn, stack, "CREATE_COMPLETE");
                String before = client.getLifecyclePolicy(request -> request.repositoryName(repository))
                        .lifecyclePolicyText();
                properties.putObject("LifecyclePolicy").put("LifecyclePolicyText", "");
                cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
                awaitStatus(cfn, stack, "UPDATE_ROLLBACK_COMPLETE");
                assertEquals(before, client.getLifecyclePolicy(request -> request.repositoryName(repository))
                        .lifecyclePolicyText());
                properties.remove("LifecyclePolicy");
                cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
                awaitStatus(cfn, stack, "UPDATE_COMPLETE");
                assertThrows(LifecyclePolicyNotFoundException.class,
                        () -> client.getLifecyclePolicy(request -> request.repositoryName(repository)));
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                awaitStackDeleted(stack);
                assertThrows(RepositoryNotFoundException.class,
                        () -> client.describeRepositories(request -> request.repositoryNames(repository)));
            }
        }
    }

    static Stream<Object[]> invalidPolicies() {
        return Stream.of(Region.US_EAST_1, Region.US_WEST_2).flatMap(region -> Stream.of(
                new Object[]{region, "{\"rules\":[]}"},
                new Object[]{region, "{\"rules\":[" + RULE + "," + RULE + "]}"},
                new Object[]{region, "[]"},
                new Object[]{region, "{\"rules\":["},
                new Object[]{region, policyWithPriority("")},
                new Object[]{region, policyWithPriority("\"rulePriority\":\"1\",")},
                new Object[]{region, policyWithPriority("\"rulePriority\":1.0,")},
                new Object[]{region, policyWithPriority("\"rulePriority\":0,")},
                new Object[]{region, policyWithPriority("\"rulePriority\":-1,")},
                new Object[]{region, policyWithPriority("\"rulePriority\":null,")},
                new Object[]{region, policyWithPriority("\"rulePriority\":2147483648,")}));
    }

    private static String policyWithPriority(String property) {
        return "{\"rules\":[" + RULE.replace("\"rulePriority\":1,", property) + "]}";
    }

    static Stream<Region> regions() {
        return Stream.of(Region.US_EAST_1, Region.US_WEST_2);
    }

    @ParameterizedTest
    @MethodSource("regions")
    void invalidDocumentIsRejectedBeforeLookingUpTheRepository(Region region) {
        String repository = "lifecycle-validation/" + UUID.randomUUID();
        try (EcrClient client = AwsTestClients.create(EcrClient.builder(), "test", region)) {
            assertThrows(InvalidParameterException.class,
                    () -> client.putLifecyclePolicy(r -> r.repositoryName(repository)
                            .lifecyclePolicyText("{\"rules\":[]}" + " ".repeat(100))));
            assertThrows(RepositoryNotFoundException.class,
                    () -> client.putLifecyclePolicy(r -> r.repositoryName(repository)
                            .lifecyclePolicyText(VALID_POLICY)));
        }
    }

    @ParameterizedTest
    @MethodSource("invalidPolicies")
    void rejectedPolicyPreservesThePreviouslyAcceptedDocument(Region region, String invalidPolicy) {
        String repository = "lifecycle-validation/" + UUID.randomUUID();
        try (EcrClient client = AwsTestClients.create(EcrClient.builder(), "test", region)) {
            client.createRepository(r -> r.repositoryName(repository));
            try {
                assertEquals(VALID_POLICY, client.putLifecyclePolicy(r -> r.repositoryName(repository)
                        .lifecyclePolicyText(VALID_POLICY)).lifecyclePolicyText());
                assertEquals(DISTINCT_PRIORITIES, client.putLifecyclePolicy(r -> r.repositoryName(repository)
                        .lifecyclePolicyText(DISTINCT_PRIORITIES)).lifecyclePolicyText());
                String padded = invalidPolicy + " ".repeat(Math.max(0, 100 - invalidPolicy.length()));
                assertAll(
                        () -> assertThrows(InvalidParameterException.class,
                                () -> client.putLifecyclePolicy(r -> r.repositoryName(repository)
                                        .lifecyclePolicyText(padded))),
                        () -> assertEquals(DISTINCT_PRIORITIES, client.getLifecyclePolicy(
                                r -> r.repositoryName(repository)).lifecyclePolicyText()));
            } finally {
                client.deleteRepository(r -> r.repositoryName(repository));
                assertThrows(RepositoryNotFoundException.class,
                        () -> client.describeRepositories(r -> r.repositoryNames(repository)));
            }
        }
    }
}
