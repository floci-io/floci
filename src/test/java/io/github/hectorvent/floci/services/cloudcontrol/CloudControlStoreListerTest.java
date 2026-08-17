package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The generic Cloud Control read side: a type nobody hand-wrote a lister for is answered from the
 * owning service's own store. AWS::ECS::TaskDefinition is the worked example because it is the one
 * that surfaced the gap — describe-task-definition showed the object and list-resources was empty.
 */
class CloudControlStoreListerTest {

    private static final String ACCOUNT = "000000000000";

    private static TaskDefinition taskDefinition(String arn, String family, int revision) {
        TaskDefinition td = new TaskDefinition();
        td.setTaskDefinitionArn(arn);
        td.setFamily(family);
        td.setRevision(revision);
        td.setStatus("ACTIVE");
        td.setExecutionRoleArn("arn:aws:iam::" + ACCOUNT + ":role/some-other-resource");
        return td;
    }

    private static CloudControlStoreLister listerOver(String service, String file, Object... values) {
        AccountAwareStorageBackend<Object> backend = AccountAwareStorageBackend.inMemory(ACCOUNT);
        for (int i = 0; i < values.length; i++) {
            backend.put("k" + i, values[i]);
        }
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.ownedBackends())
                .thenReturn(List.of(new StorageFactory.OwnedBackend(service, file, backend)));
        return new CloudControlStoreLister(factory, new ObjectMapper());
    }

    @Test
    void listsATaskDefinitionByItsOwnArn() {
        String arn = "arn:aws:ecs:us-east-1:" + ACCOUNT + ":task-definition/web:1";
        CloudControlStoreLister lister = listerOver("ecs", "ecs-task-definitions.json",
                taskDefinition(arn, "web", 1));

        List<CloudControlService.ResourceDescription> found =
                lister.list("us-east-1", "AWS::ECS::TaskDefinition");

        assertEquals(1, found.size());
        assertEquals(arn, found.getFirst().identifier());
    }

    @Test
    void reportsThePropertiesInCloudFormationCasing() throws Exception {
        String arn = "arn:aws:ecs:us-east-1:" + ACCOUNT + ":task-definition/web:1";
        CloudControlStoreLister lister = listerOver("ecs", "ecs-task-definitions.json",
                taskDefinition(arn, "web", 1));

        JsonNode model = new ObjectMapper()
                .readTree(lister.list("us-east-1", "AWS::ECS::TaskDefinition").getFirst().properties());

        assertEquals(arn, model.path("TaskDefinitionArn").asText());
        assertEquals("web", model.path("Family").asText());
        assertEquals(1, model.path("Revision").asInt());
        assertTrue(model.path("family").isMissingNode());
    }

    @Test
    void reshapesTagsIntoTheCloudFormationList() throws Exception {
        String arn = "arn:aws:ecs:us-east-1:" + ACCOUNT + ":task-definition/web:1";
        TaskDefinition td = taskDefinition(arn, "web", 1);
        td.setTags(Map.of("owner", "platform"));
        CloudControlStoreLister lister = listerOver("ecs", "ecs-task-definitions.json", td);

        JsonNode tags = new ObjectMapper()
                .readTree(lister.list("us-east-1", "AWS::ECS::TaskDefinition").getFirst().properties())
                .path("Tags");

        assertTrue(tags.isArray());
        assertEquals(1, tags.size());
        assertEquals("owner", tags.get(0).path("Key").asText());
        assertEquals("platform", tags.get(0).path("Value").asText());
    }

    @Test
    void leavesOutAResourceInAnotherRegion() {
        CloudControlStoreLister lister = listerOver("ecs", "ecs-task-definitions.json",
                taskDefinition("arn:aws:ecs:us-west-2:" + ACCOUNT + ":task-definition/web:1", "web", 1));

        assertTrue(lister.list("us-east-1", "AWS::ECS::TaskDefinition").isEmpty());
    }

    @Test
    void leavesOutAModelOfADifferentTypeInTheSameStore() {
        EcsCluster cluster = new EcsCluster();
        cluster.setClusterArn("arn:aws:ecs:us-east-1:" + ACCOUNT + ":cluster/prod");
        cluster.setClusterName("prod");
        CloudControlStoreLister lister = listerOver("ecs", "ecs.json", cluster);

        assertTrue(lister.list("us-east-1", "AWS::ECS::TaskDefinition").isEmpty());
        assertEquals(1, lister.list("us-east-1", "AWS::ECS::Cluster").size());
    }

    @Test
    void leavesOutAStoreBelongingToAnotherService() {
        CloudControlStoreLister lister = listerOver("eks", "eks.json",
                taskDefinition("arn:aws:ecs:us-east-1:" + ACCOUNT + ":task-definition/web:1", "web", 1));

        assertTrue(lister.list("us-east-1", "AWS::ECS::TaskDefinition").isEmpty());
    }

    @Test
    void matchesAPrefixedModelClassButNotAnUnrelatedSuffix() {
        assertTrue(CloudControlStoreLister.isOfType("TaskDefinition", "TaskDefinition", "ecs"));
        assertTrue(CloudControlStoreLister.isOfType("EcsCluster", "Cluster", "ecs"));
        assertTrue(CloudControlStoreLister.isOfType("EcsServiceModel", "Service", "ecs"));
        assertTrue(CloudControlStoreLister.isOfType("MediaLiveMultiplex", "Multiplex", "medialive"));
        assertFalse(CloudControlStoreLister.isOfType("DaemonTaskDefinition", "Definition", "ecs"));
        assertFalse(CloudControlStoreLister.isOfType("ContainerInstance", "Instance", "ecs"));
        assertFalse(CloudControlStoreLister.isOfType("Cluster", "EcsCluster", "ecs"));
        // A model whose class carries a suffix its AWS type does not.
        assertTrue(CloudControlStoreLister.isOfType("TableDefinition", "Table", "dynamodb"));
        assertTrue(CloudControlStoreLister.isOfType("DeliveryStreamDescription", "DeliveryStream", "firehose"));
        assertTrue(CloudControlStoreLister.isOfType("TaskDefinition", "TaskDefinition", "ecs"));
    }

    /**
     * The suffix rule alone would answer AWS::ECS::Task with task definitions, because stripping
     * Definition leaves Task. The strict pass is what stops that: a class named for the type as
     * written is found first and the lenient pass never runs.
     */
    @Test
    void prefersAClassNamedForTheTypeOverOneReachedByStrippingASuffix() {
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:" + ACCOUNT + ":task/prod/abc");
        CloudControlStoreLister lister = listerOver("ecs", "ecs.json",
                task,
                taskDefinition("arn:aws:ecs:us-east-1:" + ACCOUNT + ":task-definition/web:1", "web", 1));

        List<CloudControlService.ResourceDescription> found = lister.list("us-east-1", "AWS::ECS::Task");

        assertEquals(1, found.size());
        assertEquals("arn:aws:ecs:us-east-1:" + ACCOUNT + ":task/prod/abc", found.getFirst().identifier());
    }
}
