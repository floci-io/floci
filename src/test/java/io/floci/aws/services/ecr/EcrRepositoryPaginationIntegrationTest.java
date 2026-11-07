package io.floci.aws.services.ecr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecr.model.Repository;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.DescribeRepositoriesResponse;
import software.amazon.awssdk.services.ecr.model.InvalidParameterException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class EcrRepositoryPaginationIntegrationTest {

    private static final String ACCOUNT = "741676060001";
    private static final Region REGION = Region.US_EAST_1;

    @InjectMock
    EcrRegistryManager registryManager;
    @Inject
    StorageFactory storageFactory;

    @Test
    void sdkPagesRepositoriesWithoutRepeatingOrDroppingThem() {
        AccountAwareStorageBackend<Repository> store = storageFactory.create("ecr", "repositories.json",
                new TypeReference<Map<String, Repository>>() {});
        List<String> names = new ArrayList<>(List.of("pagination-7606-a", "pagination-7606-b"));
        try (EcrClient client = AwsTestClients.create(EcrClient.builder(), ACCOUNT, REGION)) {
            for (String name : names) {
                client.createRepository(request -> request.repositoryName(name));
            }
            DescribeRepositoriesResponse first = client.describeRepositories(request -> request.maxResults(1));
            assertEquals(1, first.repositories().size());
            assertNotNull(first.nextToken());
            DescribeRepositoriesResponse second = client.describeRepositories(request ->
                    request.maxResults(1).nextToken(first.nextToken()));
            assertEquals(1, second.repositories().size());
            assertNull(second.nextToken());
            Set<String> returned = new HashSet<>();
            first.repositories().forEach(repository -> returned.add(repository.repositoryName()));
            second.repositories().forEach(repository -> returned.add(repository.repositoryName()));
            assertEquals(Set.copyOf(names), returned);
            assertEquals(2, client.describeRepositories(request -> request.repositoryNames(names))
                    .repositories().size());
            assertThrows(InvalidParameterException.class, () -> client.describeRepositories(request ->
                    request.repositoryNames(names).maxResults(1)));
            assertThrows(InvalidParameterException.class, () -> client.describeRepositories(request ->
                    request.repositoryNames(names).nextToken(first.nextToken())));
            for (int limit : List.of(0, -1, 1001)) {
                assertThrows(InvalidParameterException.class, () -> client.describeRepositories(request ->
                        request.maxResults(limit)));
            }
            for (String token : List.of("", "invalid")) {
                assertThrows(InvalidParameterException.class, () -> client.describeRepositories(request ->
                        request.nextToken(token)));
            }
            try (EcrClient otherRegion = AwsTestClients.create(EcrClient.builder(), ACCOUNT, Region.US_WEST_2);
                 EcrClient otherAccount = AwsTestClients.create(EcrClient.builder(), "741676060002", REGION)) {
                assertThrows(InvalidParameterException.class, () -> otherRegion.describeRepositories(request ->
                        request.nextToken(first.nextToken())));
                assertThrows(InvalidParameterException.class, () -> otherAccount.describeRepositories(request ->
                        request.nextToken(first.nextToken())));
            }
            for (int index = 2; index < 101; index++) {
                String name = "pagination-7606-" + index;
                names.add(name);
                client.createRepository(request -> request.repositoryName(name));
            }
            DescribeRepositoriesResponse defaultPage = client.describeRepositories();
            assertEquals(100, defaultPage.repositories().size());
            assertNotNull(defaultPage.nextToken());
            assertEquals(1, client.describeRepositories(request -> request.nextToken(defaultPage.nextToken()))
                    .repositories().size());
            assertEquals(101, client.describeRepositories(request -> request.maxResults(1000))
                    .repositories().size());
        } finally {
            names.forEach(name -> store.deleteForAccount(ACCOUNT, REGION.id() + "::" + ACCOUNT + "::" + name));
        }
    }
}
