package io.github.hectorvent.floci.services.rum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RumResourceProviderTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private RumService service;

    @BeforeEach
    void setUp() {
        service = new RumService(new InMemoryStorage<>(), new RegionResolver(REGION, ACCOUNT));
    }

    private ObjectNode monitorRequest(String name, String domain) {
        return mapper.createObjectNode().put("Name", name).put("Domain", domain);
    }

    @Nested
    class GetResources {

        @Test
        void emptyWhenNothingStored() {
            assertTrue(service.getResources().isEmpty());
        }

        @Test
        void surfacesAppMonitorWithArnTypeRegionAccountAndTags() {
            ObjectNode request = monitorRequest("web", "example.com");
            request.set("Tags", mapper.createObjectNode().put("env", "prod"));
            service.createAppMonitor(REGION, request);

            ExplorerResource monitor = only(service.getResources(), "rum:appmonitor");
            assertEquals("arn:aws:rum:" + REGION + ":" + ACCOUNT + ":appmonitor/web", monitor.arn());
            assertEquals("rum", monitor.service());
            assertEquals(REGION, monitor.region());
            assertEquals(ACCOUNT, monitor.owningAccountId());
            assertEquals(Map.of("env", "prod"), monitor.tags());
        }

        @Test
        void surfacesMonitorsFromMultipleRegions() {
            service.createAppMonitor(REGION, monitorRequest("east", "example.com"));
            service.createAppMonitor("eu-west-1", monitorRequest("west", "example.com"));

            Map<String, String> arnsByType = service.getResources().stream()
                    .collect(Collectors.toMap(ExplorerResource::region, ExplorerResource::arn));
            assertEquals("arn:aws:rum:us-east-1:" + ACCOUNT + ":appmonitor/east",
                    arnsByType.get("us-east-1"));
            assertEquals("arn:aws:rum:eu-west-1:" + ACCOUNT + ":appmonitor/west",
                    arnsByType.get("eu-west-1"));
        }

        @Test
        void resourceWithoutTagsYieldsEmptyMapNotNull() {
            service.createAppMonitor(REGION, monitorRequest("web", "example.com"));
            ExplorerResource monitor = only(service.getResources(), "rum:appmonitor");
            assertEquals(Map.of(), monitor.tags());
        }

        @Test
        void reportsOwnerAccountCapturedAtCreationNotTheQueryingCaller() {
            MutableAccountResolver resolver = new MutableAccountResolver();
            RumService svc = new RumService(new InMemoryStorage<>(), resolver);

            resolver.accountId = "111111111111";
            svc.createAppMonitor(REGION, monitorRequest("web", "example.com"));

            // A later ListResources arrives under a different principal.
            resolver.accountId = "999999999999";
            ExplorerResource monitor = only(svc.getResources(), "rum:appmonitor");
            assertEquals("111111111111", monitor.owningAccountId());
            assertEquals("arn:aws:rum:" + REGION + ":111111111111:appmonitor/web", monitor.arn());
        }

        @Test
        void updateKeepsTheOwnerAccountCapturedAtCreation() {
            MutableAccountResolver resolver = new MutableAccountResolver();
            RumService svc = new RumService(new InMemoryStorage<>(), resolver);

            resolver.accountId = "111111111111";
            svc.createAppMonitor(REGION, monitorRequest("web", "example.com"));
            svc.updateAppMonitor(REGION, "web", monitorRequest("web", "updated.example.com"));

            resolver.accountId = "999999999999";
            ExplorerResource monitor = only(svc.getResources(), "rum:appmonitor");
            assertEquals("111111111111", monitor.owningAccountId());
            assertEquals("arn:aws:rum:" + REGION + ":111111111111:appmonitor/web", monitor.arn());
        }
    }

    private static final class MutableAccountResolver extends RegionResolver {
        private String accountId = ACCOUNT;

        MutableAccountResolver() {
            super(REGION, ACCOUNT);
        }

        @Override
        public String getAccountId() {
            return accountId;
        }
    }

    @Nested
    class GetSupportedResourceTypes {

        @Test
        void advertisesAppMonitorTypeUnderRumService() {
            Set<SupportedResourceType> types = service.getSupportedResourceTypes();
            assertEquals(Set.of("rum:appmonitor"),
                    types.stream().map(SupportedResourceType::resourceType).collect(Collectors.toSet()));
            assertTrue(types.stream().allMatch(t -> t.service().equals("rum")));
            assertTrue(types.stream().allMatch(SupportedResourceType::supportsTags));
        }
    }

    private static ExplorerResource only(List<ExplorerResource> resources, String resourceType) {
        return resources.stream()
                .filter(r -> r.resourceType().equals(resourceType))
                .reduce((a, b) -> { throw new AssertionError("expected exactly one " + resourceType); })
                .orElseThrow(() -> new AssertionError("no " + resourceType + " resource"));
    }
}
