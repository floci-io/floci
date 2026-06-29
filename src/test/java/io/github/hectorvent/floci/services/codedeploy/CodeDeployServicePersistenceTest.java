package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.ssm.SsmCommandService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies CodeDeploy durable resources survive a restart, including the nested deployment groups
 * and the built-in deployment configs. Two service instances share the same {@link StorageFactory}
 * backends; the second simulates a process restart reloading from disk.
 */
class CodeDeployServicePersistenceTest {

    private static final String REGION = "us-east-1";

    @Test
    void durableResourcesAndBuiltInsSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeDeployService first = serviceWithStorage(storage);
        first.createApplication(REGION, "web-app", "Server",
                List.of(Map.of("Key", "team", "Value", "platform")));
        first.createDeploymentGroup(REGION, "web-app", "prod-group",
                "CodeDeployDefault.OneAtATime", "arn:aws:iam::000000000000:role/cd", null);
        first.createDeploymentConfig(REGION, "custom-cfg",
                Map.of("type", "HOST_COUNT", "value", 1), "Server", null, null);
        first.registerOnPremisesInstance(REGION, "onprem-1",
                "arn:aws:sts::000000000000:session/s", "arn:aws:iam::000000000000:user/u");
        first.tagResource(first.applicationArn(REGION, "web-app"),
                List.of(Map.of("Key", "env", "Value", "prod")));

        CodeDeployService reloaded = serviceWithStorage(storage);

        assertEquals(List.of("web-app"), reloaded.listApplications(REGION));
        DeploymentGroup group = reloaded.getDeploymentGroup(REGION, "web-app", "prod-group");
        assertEquals("CodeDeployDefault.OneAtATime", group.getDeploymentConfigName());
        assertNotNull(reloaded.getDeploymentConfig(REGION, "custom-cfg"));
        // built-in configs survive (they are persisted, and topped up on load)
        assertNotNull(reloaded.getDeploymentConfig(REGION, "CodeDeployDefault.OneAtATime"));
        assertTrue(reloaded.listDeploymentConfigs(REGION).size() >= 18,
                "17 built-ins + custom config expected after restart");
        assertEquals("Registered",
                reloaded.getOnPremisesInstance(REGION, "onprem-1").getRegistrationStatus());
        Map<String, String> appTags = reloaded.listTagsForResource(first.applicationArn(REGION, "web-app"))
                .stream().collect(java.util.stream.Collectors.toMap(t -> t.get("Key"), t -> t.get("Value")));
        assertEquals("prod", appTags.get("env"));   // tagResource
        assertEquals("platform", appTags.get("team")); // createApplication tags
    }

    @Test
    void deletesAndUntagArePersistedAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeDeployService first = serviceWithStorage(storage);
        first.createApplication(REGION, "keep-app", "Server", null);
        first.createApplication(REGION, "drop-app", "Server", null);
        first.createDeploymentGroup(REGION, "keep-app", "g1", null, "arn:r", null);
        first.createDeploymentGroup(REGION, "keep-app", "g2", null, "arn:r", null);
        first.deleteApplication(REGION, "drop-app");
        first.deleteDeploymentGroup(REGION, "keep-app", "g2");
        String arn = first.applicationArn(REGION, "keep-app");
        first.tagResource(arn, List.of(Map.of("Key", "env", "Value", "prod"),
                Map.of("Key", "team", "Value", "sec")));
        first.untagResource(arn, List.of("team"));

        CodeDeployService reloaded = serviceWithStorage(storage);

        assertEquals(List.of("keep-app"), reloaded.listApplications(REGION));
        assertEquals(List.of("g1"), reloaded.listDeploymentGroups(REGION, "keep-app"));
        assertThrows(Exception.class, () -> reloaded.getDeploymentGroup(REGION, "keep-app", "g2"));
        Map<String, String> tags = reloaded.listTagsForResource(arn).stream()
                .collect(java.util.stream.Collectors.toMap(t -> t.get("Key"), t -> t.get("Value")));
        assertEquals("prod", tags.get("env"));
        assertTrue(!tags.containsKey("team"), "untagged key must not reappear after restart");
    }

    private static CodeDeployService serviceWithStorage(StorageFactory storage) {
        CodeDeployService service = new CodeDeployService(
                mock(LambdaService.class), mock(EcsService.class), mock(ElbV2Service.class),
                mock(SsmCommandService.class), mock(Ec2Service.class), new ObjectMapper(),
                new RegionResolver(REGION, "000000000000"), storage);
        service.initializeStorage();
        return service;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> StorageBackend<String, V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName, ignored -> new InMemoryStorage<>());
        }
    }
}
