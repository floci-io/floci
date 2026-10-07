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
        Map<String, String> tags,
        List<String> securityGroups,
        List<String> subnetIds,
        Map<String, Object> logs,
        Map<String, Object> maintenanceWindowStartTime,
        String storageType,
        String authenticationStrategy,
        Map<String, Object> encryptionOptions,
        Map<String, Object> configuration) {

    /** The members every broker needs; the optional ones default to absent. */
    public CreateBrokerParams(String brokerName, String engineType, String engineVersion,
                              String deploymentMode, String hostInstanceType,
                              boolean publiclyAccessible, boolean autoMinorVersionUpgrade,
                              List<MqUser> users, Map<String, String> tags) {
        this(brokerName, engineType, engineVersion, deploymentMode, hostInstanceType,
                publiclyAccessible, autoMinorVersionUpgrade, users, tags,
                null, null, null, null, null, null, null, null);
    }
}
