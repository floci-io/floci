package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.InvalidParameterException;
import software.amazon.awssdk.services.ecr.model.RepositoryNotFoundException;

import java.net.URI;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class EcrLifecyclePolicyValidationIntegrationTest {

    private static final String RULE = """
            {"rulePriority":1,"selection":{"tagStatus":"any","countType":"imageCountMoreThan",
            "countNumber":2},"action":{"type":"expire"}}
            """;
    private static final String VALID_POLICY = "{\"rules\":[" + RULE + "]}";
    private static final String DISTINCT_PRIORITIES = "{\"rules\":["
            + RULE.replace("\"tagStatus\":\"any\"", "\"tagStatus\":\"untagged\"") + ","
            + RULE.replace("\"rulePriority\":1", "\"rulePriority\":2") + "]}";

    @TestHTTPResource("/")
    URI endpoint;

    @InjectMock
    EcrRegistryManager registry;

    @BeforeEach
    void keepRepositoryMetadataIndependentOfDocker() {
        when(registry.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenReturn("registry.invalid/lifecycle-validation");
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
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
        try (EcrClient client = EcrClient.builder().endpointOverride(endpoint)
                .region(region).credentialsProvider(credentials).build()) {
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
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
        try (EcrClient client = EcrClient.builder().endpointOverride(endpoint)
                .region(region).credentialsProvider(credentials).build()) {
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
