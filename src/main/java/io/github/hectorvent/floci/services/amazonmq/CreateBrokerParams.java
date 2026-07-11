package io.github.hectorvent.floci.services.amazonmq;

import io.github.hectorvent.floci.services.amazonmq.model.MqUser;

import java.util.List;
import java.util.Map;

/**
 * Immutable carrier for the fields the controller parses out of a CreateBroker
 * request body. A record fits here because the request is read once and never
 * mutated — the opposite of {@link io.github.hectorvent.floci.services.amazonmq.model.Broker},
 * whose state evolves after creation.
 */
public record CreateBrokerParams(
        String brokerName,
        String engineType,
        String engineVersion,
        String deploymentMode,
        String hostInstanceType,
        boolean publiclyAccessible,
        boolean autoMinorVersionUpgrade,
        List<MqUser> users,
        Map<String, String> tags) {
}
