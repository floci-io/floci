package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
        when(dynamoDbService.applyReplicaUpdates(
                "global-table", List.of("eu-west-1"), List.of(), "us-east-1"))
                .thenReturn(null);
        when(dynamoDbService.applyReplicaUpdates(
                "global-table", List.of(), List.of("us-west-2"), "us-east-1"))
                .thenThrow(new AwsException("InternalError", "simulated removal failure", 500));

        StackResource resource = provisioner.provision(
                "Replica", "Custom::DynamoDBReplica",
                mapper.readTree("""
                        {"TableName":"global-table","Region":"eu-west-1"}
                        """),
                engine(), "us-east-1", "000000000000", "my-stack", "us-west-2", Map.of());

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertEquals("simulated removal failure", resource.getStatusReason());
        assertEquals("us-west-2", resource.getPhysicalId());
        InOrder calls = inOrder(dynamoDbService);
        calls.verify(dynamoDbService).applyReplicaUpdates(
                "global-table", List.of("eu-west-1"), List.of(), "us-east-1");
        calls.verify(dynamoDbService).applyReplicaUpdates(
                "global-table", List.of(), List.of("us-west-2"), "us-east-1");
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                "stack/id", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }
}
