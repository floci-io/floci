package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudwatch.logs.model.LogGroup;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CloudWatchLogsGroupPageSortTest {

    @Test
    void aLargePageStaysWithinOneSortsNameReadBudget() {
        AtomicInteger nameReads = new AtomicInteger();
        List<LogGroup> groups = new ArrayList<>();
        for (int i = 999; i >= 0; i--) {
            LogGroup group = new LogGroup() {
                @Override
                public String getLogGroupName() {
                    nameReads.incrementAndGet();
                    return super.getLogGroupName();
                }
            };
            group.setLogGroupName(String.format("group-%03d", i));
            groups.add(group);
        }
        InMemoryStorage<String, LogGroup> groupStore = new InMemoryStorage<>() {
            @Override
            public List<LogGroup> scan(Predicate<String> keyFilter) {
                return new ArrayList<>(groups);
            }
        };
        RegionResolver resolver = new RegionResolver("us-east-1", "000000000000");
        CloudWatchLogsService service = new CloudWatchLogsService(groupStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                10_000, resolver);
        ObjectMapper mapper = new ObjectMapper();
        CloudWatchLogsHandler handler = new CloudWatchLogsHandler(service,
                new CloudWatchLogsCrossAccountService(new InMemoryStorage<>(), new InMemoryStorage<>(), resolver, mapper),
                new CloudWatchLogsMetricFilterService(service, mock(CloudWatchMetricsService.class), resolver), mapper);
        List<Integer> measurements = new ArrayList<>();
        for (int sample = 0; sample < 3; sample++) {
            nameReads.set(0);
            try (Response response = handler.handle("DescribeLogGroups", mapper.createObjectNode(), "us-east-1")) {
                ObjectNode entity = (ObjectNode) response.getEntity();
                assertEquals(50, entity.path("logGroups").size());
                assertEquals("group-000", entity.path("logGroups").get(0).path("logGroupName").asText());
                assertEquals("group-049", entity.path("logGroups").get(49).path("logGroupName").asText());
                assertTrue(entity.has("nextToken"));
            }
            measurements.add(nameReads.get());
        }
        // A single TimSort run over 1000 reverse-ordered names takes about 2000 reads; allow page rendering too.
        assertTrue(measurements.stream().allMatch(reads -> reads < 3000),
                "Redundant full-list sorting exceeds the name-read budget: " + measurements);
        List<LogGroup> unpaged = service.describeLogGroups(null, "us-east-1");
        assertEquals("group-000", unpaged.getFirst().getLogGroupName());
        assertEquals("group-999", unpaged.getLast().getLogGroupName());
    }
}
