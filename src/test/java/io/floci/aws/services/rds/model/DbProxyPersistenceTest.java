package io.floci.aws.services.rds.model;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.PersistentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbProxyPersistenceTest {

    @Test
    void proxyPersistsAcrossInstances(@TempDir Path directory) {
        Path file = directory.resolve("rds-proxies.json");
        DbProxy proxy = new DbProxy();
        proxy.setDbProxyName("restart-proxy");
        proxy.setEngineFamily("POSTGRESQL");
        proxy.setEndpointHost("restart-proxy.proxy-000000000000.us-east-1.rds.amazonaws.com");
        proxy.setProxyPort(5433);

        new PersistentStorage<String, DbProxy>(file, new TypeReference<Map<String, DbProxy>>() {})
                .put(proxy.getDbProxyName(), proxy);

        PersistentStorage<String, DbProxy> reader = new PersistentStorage<>(
                file, new TypeReference<Map<String, DbProxy>>() {});
        reader.load();

        DbProxy restored = reader.get("restart-proxy").orElseThrow();
        assertEquals("POSTGRESQL", restored.getEngineFamily());
        assertEquals("restart-proxy.proxy-000000000000.us-east-1.rds.amazonaws.com", restored.getEndpoint());
        assertEquals(5433, restored.getProxyPort());
    }
}
