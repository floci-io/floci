package io.floci.aws.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.eventbridge.model.EventBus;
import io.floci.aws.services.eventbridge.model.Rule;
import io.floci.aws.services.eventbridge.model.Target;

import java.util.List;

final class EventBridgeTargetLookupFixtures {
    private EventBridgeTargetLookupFixtures() {}

    static EventBridgeService service(StorageBackend<String, EventBus> buses, StorageBackend<String, Rule> rules,
                                      StorageBackend<String, List<Target>> targets, RegionResolver resolver) {
        return new EventBridgeService(buses, rules, targets, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), resolver,
                new ObjectMapper(), null, null, null, null, null);
    }
}
