package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Api;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Call;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one DynamoDB backend CDI selects, and the one Resource Explorer provider in front of it. */
@QuarkusTest
class DynamoDbBackendSelectionTest {

    private static final String REGION = "us-east-1";
    private static final Scope ACCOUNT_A = new Scope("210987654321", REGION);
    private static final Scope ACCOUNT_B = new Scope("321098765432", REGION);

    @Inject
    DynamoDbOperations operations;

    @Inject
    DynamoDbItemAccess items;

    @Inject
    DynamoDbTableAccess tables;

    // Collected exactly as ResourceExplorer2Service collects its providers.
    @Inject
    Instance<ResourceProvider> providers;

    @Inject
    ObjectMapper mapper;

    @Test
    void everyCapabilityIsTheOneNativeBackend() {
        Object backend = ClientProxy.unwrap(operations);

        assertInstanceOf(NativeDynamoDbBackend.class, backend);
        assertSame(backend, ClientProxy.unwrap(items));
        assertSame(backend, ClientProxy.unwrap(tables));
    }

    @Test
    void theFacadeIsTheOnlyDynamoDbTableProvider() {
        List<ResourceProvider> dynamoDbProviders = providers.stream()
                .filter(provider -> provider.getSupportedResourceTypes().stream()
                        .anyMatch(type -> "dynamodb:table".equals(type.resourceType())))
                .toList();

        assertEquals(1, dynamoDbProviders.size());
        assertInstanceOf(DynamoDbFacade.class, ClientProxy.unwrap(dynamoDbProviders.get(0)));
    }

    @Test
    void callsRunUnderTheScopeAccount() throws Exception {
        String tableName = "backend-scope-" + UUID.randomUUID();
        tables.createTable(ACCOUNT_A, tableName, List.of(new KeySchemaElement("id", "HASH")),
                List.of(new AttributeDefinition("id", "S")), 5L, 5L, List.of(), List.of());
        try {
            assertTrue(tables.findTable(ACCOUNT_B, tableName).isEmpty());
            assertTrue(tables.findTable(ACCOUNT_A, tableName).isPresent());

            ObjectNode body = mapper.createObjectNode().put("TableName", tableName);
            AwsException notFound = assertThrows(AwsException.class,
                    () -> operations.execute(new Call(ACCOUNT_B, Api.DYNAMODB, "DescribeTable", body)));
            assertEquals("ResourceNotFoundException", notFound.getErrorCode());
            assertEquals(200, operations.execute(new Call(ACCOUNT_A, Api.DYNAMODB, "DescribeTable", body)).status());
        } finally {
            tables.deleteTable(ACCOUNT_A, tableName);
        }
    }
}
