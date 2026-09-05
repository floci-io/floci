package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamConditionContextResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService dynamoDbService;
    private Instance<DynamoDbService> dynamoDbServiceInstance;
    private RequestContext requestContext;
    private EmulatorConfig config;
    private IamConditionContextResolver resolver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        dynamoDbService = mock(DynamoDbService.class);
        dynamoDbServiceInstance = mock(Instance.class);
        when(dynamoDbServiceInstance.isResolvable()).thenReturn(true);
        when(dynamoDbServiceInstance.get()).thenReturn(dynamoDbService);
        requestContext = new RequestContext();
        requestContext.setRegion("us-east-1");
        config = mock(EmulatorConfig.class);
        when(config.defaultRegion()).thenReturn("us-east-1");
        resolver = new IamConditionContextResolver(
                dynamoDbServiceInstance, requestContext, config);
    }

    private TableDefinition fgacTable() {
        TableDefinition table = new TableDefinition();
        table.setTableName("FgacTable");
        table.setKeySchema(List.of(new KeySchemaElement("PK", "HASH")));
        return table;
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolvesS3ListBucketQueryConditionContext() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("prefix", "my_namespace/table/");
        query.add("delimiter", "/");
        query.add("max-keys", "100");

        when(containerRequest.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getQueryParameters()).thenReturn(query);

        Map<String, List<String>> conditions =
                resolver.resolve("s3", "s3:ListBucket", containerRequest);

        assertEquals(List.of("my_namespace/table/"), conditions.get("s3:prefix"));
        assertEquals(List.of("/"), conditions.get("s3:delimiter"));
        assertEquals(List.of("100"), conditions.get("s3:max-keys"));
    }

    @Test
    void s3BucketListConditionContextReturnsNullWhenNoSupportedQueryParametersArePresent() {
        assertNull(resolver.s3BucketListConditionContext(new MultivaluedHashMap<>()));
    }

    @Test
    void resolveReturnsNullForUnsupportedServiceOrAction() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);

        assertNull(resolver.resolve("lambda", "lambda:InvokeFunction", containerRequest));
        assertNull(resolver.resolve("s3", "s3:GetObject", containerRequest));
    }

    @Test
    void resolvesDynamoDbLeadingKeysForGetItem() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"FgacTable","Key":{"PK":{"S":"USER_alice"}}}"""));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest);

        assertEquals(List.of("USER_alice"), conditions.get("dynamodb:LeadingKeys"));
        assertEquals(List.of("PK"), conditions.get("dynamodb:Attributes"));
        assertFalse(conditions.containsKey("dynamodb:Select"));
    }

    @Test
    void omitsLeadingKeysWhenTheTableIsUnknown() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"MissingTable","Key":{"PK":{"S":"USER_alice"}}}"""));
        when(dynamoDbService.findTable(eq("MissingTable"), anyString()))
                .thenReturn(Optional.empty());

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest);

        assertFalse(conditions.containsKey("dynamodb:LeadingKeys"));
        // Attribute names do not depend on the key schema, so they are still populated.
        assertEquals(List.of("PK"), conditions.get("dynamodb:Attributes"));
    }

    @Test
    void carriesSelectForQuery() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"FgacTable",
                 "KeyConditionExpression":"PK = :v",
                 "ExpressionAttributeValues":{":v":{"S":"USER_alice"}},
                 "Select":"COUNT"}"""));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:Query", containerRequest);

        assertEquals(List.of("USER_alice"), conditions.get("dynamodb:LeadingKeys"));
        assertEquals(List.of("COUNT"), conditions.get("dynamodb:Select"));
    }

    @Test
    void returnsNullWhenNoBodyWasBuffered() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(null);

        assertNull(resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest));
    }

    @Test
    void resolvesBatchGetItemAcrossEveryRequestedKey() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"RequestItems":{"FgacTable":{"Keys":[
                   {"PK":{"S":"USER_alice"}},{"PK":{"S":"USER_bob"}}]}}}"""));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:BatchGetItem", containerRequest);

        assertEquals(List.of("USER_alice", "USER_bob"), conditions.get("dynamodb:LeadingKeys"));
    }

    @Test
    void omitsLeadingKeysForAMultiTableBatch() {
        // A batch spanning two tables has no single key schema: applying table A's partition
        // key name to table B's items would be wrong, so no leading keys are produced.
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"RequestItems":{
                   "FgacTable":{"Keys":[{"PK":{"S":"USER_alice"}}]},
                   "OtherTable":{"Keys":[{"PK":{"S":"USER_bob"}}]}}}"""));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:BatchGetItem", containerRequest);

        assertFalse(conditions.containsKey("dynamodb:LeadingKeys"));
    }
}
