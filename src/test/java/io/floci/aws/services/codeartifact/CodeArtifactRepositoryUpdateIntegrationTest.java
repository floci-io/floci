package io.floci.aws.services.codeartifact;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codeartifact.CodeartifactClient;
import software.amazon.awssdk.services.codeartifact.model.RepositoryDescription;
import software.amazon.awssdk.services.codeartifact.model.UpstreamRepository;
import software.amazon.awssdk.services.codeartifact.model.UpstreamRepositoryInfo;
import software.amazon.awssdk.services.codeartifact.model.ValidationException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class CodeArtifactRepositoryUpdateIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusedUpdatePreservesTheDescriptionAndUpstreams(boolean clearUpstreams) {
        String domain = "ca-update-" + Long.toString(System.nanoTime(), 36);
        List<String> createdRepositories = new ArrayList<>();
        try (CodeartifactClient client = AwsTestClients.create(
                CodeartifactClient.builder(), "741673700001", Region.US_EAST_1)) {
            client.createDomain(request -> request.domain(domain));
            try {
                for (String repository : List.of("existing", "replacement")) {
                    client.createRepository(request -> request.domain(domain).repository(repository));
                    createdRepositories.add(repository);
                }
                client.createRepository(request -> request.domain(domain).repository("consumer")
                        .description("original")
                        .upstreams(UpstreamRepository.builder().repositoryName("existing").build()));
                createdRepositories.add("consumer");
                List<UpstreamRepository> requestedUpstreams = clearUpstreams ? List.of()
                        : List.of(UpstreamRepository.builder().repositoryName("replacement").build());

                ValidationException failure = assertThrows(ValidationException.class,
                        () -> client.updateRepository(request -> request.domain(domain).repository("consumer")
                                .description("x".repeat(1001)).upstreams(requestedUpstreams)));

                assertEquals(400, failure.statusCode());
                assertEquals("ValidationException", failure.awsErrorDetails().errorCode());
                RepositoryDescription unchanged = client.describeRepository(request -> request.domain(domain)
                        .repository("consumer")).repository();
                assertEquals("original", unchanged.description());
                assertEquals(List.of("existing"),
                        unchanged.upstreams().stream().map(UpstreamRepositoryInfo::repositoryName).toList());

                String acceptedDescription = "x".repeat(1000);
                client.updateRepository(request -> request.domain(domain).repository("consumer")
                        .description(acceptedDescription).upstreams(requestedUpstreams));
                RepositoryDescription updated = client.describeRepository(request -> request.domain(domain)
                        .repository("consumer")).repository();
                assertEquals(acceptedDescription, updated.description());
                assertEquals(clearUpstreams ? List.of() : List.of("replacement"),
                        updated.upstreams().stream().map(UpstreamRepositoryInfo::repositoryName).toList());
            } finally {
                for (String repository : createdRepositories.reversed()) {
                    client.deleteRepository(request -> request.domain(domain).repository(repository));
                }
                client.deleteDomain(request -> request.domain(domain));
            }
        }
    }
}
