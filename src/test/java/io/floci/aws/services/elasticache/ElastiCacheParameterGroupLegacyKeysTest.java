package io.floci.aws.services.elasticache;

import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.elasticache.model.CacheParameterGroup;
import io.floci.aws.services.elasticache.model.ReplicationGroup;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cache parameter groups stored before they were keyed by Region, under their bare name, move at startup to the
 * default Region, and every other Region where one of the account's replication groups names one gets a copy of it,
 * so that group still finds it there.
 */
class ElastiCacheParameterGroupLegacyKeysTest {

    private static final String ACCOUNT = "000000000000";

    @Test
    @SuppressWarnings("unchecked")
    void aLegacyGroupMovesToTheDefaultRegionAndIsCopiedToTheRegionOfAReplicationGroupNamingIt() {
        ElastiCacheServiceFixture fixture = new ElastiCacheServiceFixture();
        AccountAwareStorageBackend<CacheParameterGroup> parameterGroups = (AccountAwareStorageBackend<CacheParameterGroup>)
                fixture.backendsByFile.get("elasticache-parameter-groups.json");
        AccountAwareStorageBackend<ReplicationGroup> groups = (AccountAwareStorageBackend<ReplicationGroup>)
                fixture.backendsByFile.get("elasticache-groups.json");
        CacheParameterGroup used = new CacheParameterGroup("used", "redis7", "named by a group");
        used.setParameters(new LinkedHashMap<>(Map.of("maxmemory-policy", "allkeys-lru")));
        parameterGroups.put("used", used);
        parameterGroups.put("unused", new CacheParameterGroup("unused", "redis7", "named by none"));
        ReplicationGroup group = new ReplicationGroup();
        group.setReplicationGroupId("in-frankfurt");
        group.setArn("arn:aws:elasticache:eu-central-1:" + ACCOUNT + ":replicationgroup:in-frankfurt");
        group.setCacheParameterGroupName("used");
        groups.put("in-frankfurt", group);

        fixture.service.migrateLegacyParameterGroups();

        assertEquals(Set.of("us-east-1::used", "us-east-1::unused", "eu-central-1::used"),
                parameterGroups.keysForAccount(ACCOUNT));
        assertEquals(Map.of("maxmemory-policy", "allkeys-lru"),
                parameterGroups.get("eu-central-1::used").orElseThrow().getParameters());
        // Outside a request, the service answers for the default Region, us-east-1.
        assertEquals(Set.of("used", "unused"), fixture.service.describeCacheParameterGroups(null).stream()
                .map(CacheParameterGroup::getName)
                .filter(name -> !name.startsWith("default."))
                .collect(Collectors.toSet()));
    }
}
