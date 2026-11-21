package io.floci.aws.services.cloudmap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudmap.model.Namespace;
import io.floci.aws.services.cloudmap.model.Service;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/** IAM's lookup of the tags Cloud Map holds on a namespace or a service. */
class CloudMapStoredTagsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    /**
     * The lookup reads the tags of the namespace or the service the ARN names, as the tag calls leave them, a tag
     * without a value as an empty one, and those of the one the handlers find by its id for an ARN naming another
     * Region; nothing for another account's, an unknown resource's or another service's ARN.
     */
    @Test
    void storedTagsAreTheTagsOfTheNamespaceOrServiceTheArnNames() {
        CloudMapService service = service(new InMemoryStorageFactory(ACCOUNT));
        String namespaceId = service.createHttpNamespace("stored-tags", null, null,
                Map.of("team", "web", "env", "dev"), REGION).getTargets().get("NAMESPACE");
        String namespaceArn = service.getNamespace(namespaceId).getArn();
        service.tagResource(namespaceArn, Map.of("team", "data"));
        service.untagResource(namespaceArn, List.of("env"));
        Map<String, String> noValue = new HashMap<>();
        noValue.put("owner", null);
        Service svc = service.createService("stored-tags", namespaceId, null, null, null, null, null, null,
                noValue, REGION);
        service.tagResource(svc.getArn(), Map.of("team", "ops"));

        assertEquals("servicediscovery", service.namespace());
        assertEquals(Map.of("team", "data"), service.storedTags(namespaceArn));
        assertEquals(Map.of("owner", "", "team", "ops"), service.storedTags(svc.getArn()));
        assertTrue(service.storedTags(namespaceArn.replace(ACCOUNT, "111122223333")).isEmpty());
        assertEquals(Map.of("team", "data"), service.storedTags(namespaceArn.replace(REGION, "us-west-2")));
        assertTrue(service.storedTags(namespaceArn.replace(namespaceId, "ns-missing")).isEmpty());
        assertTrue(service.storedTags(svc.getArn().replace("service/", "namespace/")).isEmpty());
        assertTrue(service.storedTags("arn:aws:ec2:us-east-1:000000000000:instance/i-0123456789abcdef0").isEmpty());
    }

    /** A namespace stored before storage was account-aware is read in place: the lookup migrates nothing. */
    @Test
    void storedTagsReadAPreAccountAwareNamespaceInPlace() {
        InMemoryStorage<String, Namespace> raw = new InMemoryStorage<>();
        CloudMapService service =
                service(new InMemoryStorageFactory(ACCOUNT).withStore("cloudmap-namespaces.json", raw));
        Namespace legacy = new Namespace();
        legacy.setId("ns-legacy");
        legacy.setArn("arn:aws:servicediscovery:us-east-1:000000000000:namespace/ns-legacy");
        legacy.setTags(Map.of("team", "data"));
        raw.put("ns-legacy", legacy);

        assertEquals(Map.of("team", "data"), service.storedTags(legacy.getArn()));
        assertEquals(Set.of("ns-legacy"), raw.keys());
    }

    private CloudMapService service(InMemoryStorageFactory storage) {
        return new CloudMapService(storage, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                new RegionResolver(REGION, ACCOUNT), new ObjectMapper(), null);
    }
}
