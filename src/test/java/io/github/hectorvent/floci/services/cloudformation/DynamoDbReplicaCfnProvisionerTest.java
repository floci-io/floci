package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DynamoDbReplicaCfnProvisionerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService dynamoDbService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        dynamoDbService = mock(DynamoDbService.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, dynamoDbService, null, null, null, null, null, null,
                null, null, null, null, null, null, mapper, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new CloudFormationResourceRegistry(List.of()));
    }

    @Test
    void changingReplicaRegionFailsWithoutOverwritingPhysicalIdWhenOldRemovalFails() throws Exception {
        Set<String> replicas = new HashSet<>(Set.of("us-west-2"));
        when(dynamoDbService.applyReplicaUpdates(
                eq("global-table"), anyList(), anyList(), eq("us-east-1")))
                .thenAnswer(invocation -> {
                    List<String> additions = invocation.getArgument(1);
                    List<String> removals = invocation.getArgument(2);
                    if (removals.contains("us-west-2")) {
                        throw new AwsException("InternalError", "simulated removal failure", 500);
                    }
                    replicas.removeAll(removals);
                    replicas.addAll(additions);
                    return null;
                });

        StackResource resource = provisioner.provision(
                "Replica", "Custom::DynamoDBReplica",
                mapper.readTree("""
                        {"TableName":"global-table","Region":"eu-west-1"}
                        """),
                engine(), "us-east-1", "000000000000", "my-stack", "us-west-2", Map.of());

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertEquals("simulated removal failure", resource.getStatusReason());
        assertEquals("us-west-2", resource.getPhysicalId());
        assertEquals(Set.of("us-west-2"), replicas);
        verify(dynamoDbService).applyReplicaUpdates(
                "global-table", List.of("eu-west-1"), List.of("us-west-2"), "us-east-1");
        verifyNoMoreInteractions(dynamoDbService);
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                "stack/id", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }
}
