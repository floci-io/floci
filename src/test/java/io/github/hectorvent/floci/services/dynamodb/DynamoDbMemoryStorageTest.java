package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

class DynamoDbMemoryStorageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void writesRetainLiveMapAndReloadCurrentItems(boolean accountAware) throws Exception {
        InMemoryStorage<String, Map<String, JsonNode>> rawItems = new InMemoryStorage<>();
        InMemoryStorage<String, TableDefinition> rawTables = new InMemoryStorage<>();
        for (String account : List.of("000000000001", "000000000002")) {
            StorageBackend<String, Map<String, JsonNode>> items = accountAware
                    ? new AccountAwareStorageBackend<>(rawItems, null, account) : rawItems;
            StorageBackend<String, TableDefinition> tables = accountAware
                    ? new AccountAwareStorageBackend<>(rawTables, null, account) : rawTables;
            RegionResolver resolver = new RegionResolver("us-east-1", account);
            DynamoDbService service = new DynamoDbService(tables, items, resolver);
            String table = accountAware ? "Users" : "Users" + account;
            String storageKey = "us-east-1::" + table;
            service.createTable(table, List.of(new KeySchemaElement("pk", "HASH")),
                    List.of(new AttributeDefinition("pk", "S")), 5L, 5L, "us-east-1");
            JsonNode key = mapper.readTree("{\"pk\":{\"S\":\"one\"}}");
            service.putItem(table, key, "us-east-1");
            Map<String, JsonNode> live = items.get(storageKey).orElseThrow();
            service.updateItem(table, key, null, "SET v = :v", null,
                    mapper.readTree("{\":v\":{\"S\":\"updated\"}}"), "NONE", "us-east-1");
            assertThat(items.get(storageKey).orElseThrow(), sameInstance(live));
            assertThat(live.values().iterator().next().path("v").path("S").asText(), equalTo("updated"));
            service.batchWriteItem(Map.of(table, List.of(mapper.readTree(
                    "{\"PutRequest\":{\"Item\":{\"pk\":{\"S\":\"two\"}}}}"))), "us-east-1");
            service.transactWriteItems(List.of(mapper.readTree(
                    "{\"Put\":{\"TableName\":\"" + table + "\",\"Item\":{\"pk\":{\"S\":\"three\"}}}}")), "us-east-1");
            service.deleteItem(table, key, "us-east-1");
            assertThat(items.get(storageKey).orElseThrow(), sameInstance(live));
            assertThat(live.size(), equalTo(2));
            DynamoDbService reloaded = new DynamoDbService(tables, items, resolver);
            JsonNode second = mapper.readTree("{\"pk\":{\"S\":\"two\"}}");
            assertThat(reloaded.getItem(table, second, "us-east-1"), equalTo(second));
            service.finishRestore(table, List.of(key), "us-east-1");
            service.putItem(table, second, "us-east-1");
            assertThat(items.get(storageKey).orElseThrow().size(), equalTo(2));
            if (accountAware) {
                assertThat(rawItems.get("000000000001/" + storageKey).orElseThrow().size(), equalTo(2));
            }
        }
    }
}
