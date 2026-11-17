package io.floci.aws.services.ecs;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.TaskDefinition;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task definitions persisted before their storage key carried the region ({@code family:revision})
 * stay in their own region: they still resolve there, are re-filed on their next write, and are not
 * dropped when another region registers the same family and revision.
 */
class EcsTaskDefinitionLegacyKeyTest {

    private static final String REGION = "us-east-1";
    private static final String OTHER = "eu-west-1";
    private static final String FAMILY = "legacy-fam";
    private static final String LEGACY_ARN =
            "arn:aws:ecs:" + REGION + ":000000000000:task-definition/" + FAMILY + ":1";

    @Test
    void anotherRegionsRegistrationKeepsALegacyDefinition() {
        AccountAwareStorageBackend<TaskDefinition> taskDefinitions = taskDefinitionsStore();
        taskDefinitions.put(FAMILY + ":1", legacyDefinition());
        EcsService service = newService(taskDefinitions);

        TaskDefinition other = register(service, OTHER);

        assertEquals(1, other.getRevision());
        assertEquals(List.of(LEGACY_ARN), arns(service, REGION));
        assertEquals(List.of(other.getTaskDefinitionArn()), arns(service, OTHER));
    }

    @Test
    void aLegacyDefinitionIsRefiledOnItsNextWriteAndListedOnce() {
        AccountAwareStorageBackend<TaskDefinition> taskDefinitions = taskDefinitionsStore();
        taskDefinitions.put(FAMILY + ":1", legacyDefinition());
        EcsService service = newService(taskDefinitions);

        service.deregisterTaskDefinition(FAMILY + ":1", REGION);

        assertEquals(List.of(REGION + "::" + FAMILY + ":1"), List.copyOf(taskDefinitions.keys()));
        assertEquals(List.of(LEGACY_ARN), service.listTaskDefinitions(REGION, FAMILY, "INACTIVE", null, null, null)
                .arns());
        assertEquals(2, register(service, REGION).getRevision());
    }

    @Test
    void aStoredDefinitionWithoutAnArnBreaksNoLookup() {
        AccountAwareStorageBackend<TaskDefinition> taskDefinitions = taskDefinitionsStore();
        TaskDefinition unreadable = legacyDefinition();
        unreadable.setFamily(FAMILY + "-unreadable");
        unreadable.setTaskDefinitionArn(null);
        taskDefinitions.put(FAMILY + "-unreadable:1", unreadable);
        EcsService service = newService(taskDefinitions);

        TaskDefinition registered = register(service, REGION);

        assertEquals(List.of(registered.getTaskDefinitionArn()), arns(service, REGION));
        assertEquals(registered.getTaskDefinitionArn(),
                service.describeTaskDefinition(FAMILY + ":1", REGION).getTaskDefinitionArn());
        assertEquals(registered.getTaskDefinitionArn(),
                service.describeTaskDefinition(FAMILY, REGION).getTaskDefinitionArn());
    }

    @Test
    void aStoredDefinitionWithoutAnArnStaysInTheDefaultRegion() {
        AccountAwareStorageBackend<TaskDefinition> taskDefinitions = taskDefinitionsStore();
        TaskDefinition unreadable = legacyDefinition();
        unreadable.setTaskDefinitionArn(null);
        taskDefinitions.put(FAMILY + ":1", unreadable);
        EcsService service = newService(taskDefinitions);

        TaskDefinition other = register(service, OTHER);

        assertEquals(1, other.getRevision());
        assertEquals(other.getTaskDefinitionArn(),
                service.describeTaskDefinition(FAMILY + ":1", OTHER).getTaskDefinitionArn());
        assertEquals(List.of(other.getTaskDefinitionArn()), arns(service, OTHER));
        assertEquals(2, register(service, REGION).getRevision());
    }

    // Catches: a missing reference dereferenced while searching a populated region, a 500
    // NullPointerException instead of the ClientException any unknown reference gets.
    @Test
    void aMissingReferenceInAPopulatedRegionIsAClientException() {
        EcsService service = EcsServiceFixtures.mockModeService();
        register(service, REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.describeTaskDefinition(null, REGION));

        assertEquals("ClientException", e.getErrorCode());
    }

    private static TaskDefinition legacyDefinition() {
        TaskDefinition td = new TaskDefinition();
        td.setFamily(FAMILY);
        td.setRevision(1);
        td.setStatus("ACTIVE");
        td.setTaskDefinitionArn(LEGACY_ARN);
        return td;
    }

    private static TaskDefinition register(EcsService service, String region) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage("app:1");
        return service.registerTaskDefinition(FAMILY, List.of(cd), null, null, null, null, null, List.of(), region);
    }

    private static List<String> arns(EcsService service, String region) {
        return service.listTaskDefinitions(region, FAMILY, null, null, null, null).arns();
    }

    private static AccountAwareStorageBackend<TaskDefinition> taskDefinitionsStore() {
        return AccountAwareStorageBackend.inMemory(EcsServiceFixtures.ACCOUNT);
    }

    /** A service whose task definitions start as {@code taskDefinitions} holds them. */
    private static EcsService newService(AccountAwareStorageBackend<TaskDefinition> taskDefinitions) {
        return EcsServiceFixtures.builder()
                .storageFactory(new InMemoryStorageFactory(EcsServiceFixtures.ACCOUNT)
                        .withStore("ecs-task-definitions.json", taskDefinitions))
                .build();
    }
}
