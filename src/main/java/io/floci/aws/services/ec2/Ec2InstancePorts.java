package io.floci.aws.services.ec2;

import io.floci.aws.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The host ports of an EC2 instance that something other than the instance's own container
 * serves. An ECS task in {@code bridge} or {@code host} network mode is placed on a container
 * instance and is addressed, as on AWS, by that instance's id and the host port it publishes,
 * but its containers run beside the instance rather than inside it. ECS records here where such a
 * port is actually reachable, so an ELBv2 {@code instance} target {@code i-...:port} reaches the
 * task the way AWS forwards it to the instance's primary address on that port.
 *
 * <p>Memory only: the tasks that publish these ports do not survive a restart either.
 */
@ApplicationScoped
public class Ec2InstancePorts implements Resettable {

    /** Where a published instance port is reachable from the Floci process. */
    public record Endpoint(String host, int port) {}

    private record Key(String accountId, String instanceId, int port) {}

    private final Map<Key, Endpoint> endpoints = new ConcurrentHashMap<>();

    /**
     * Records that {@code instanceId}'s {@code port} is served at {@code endpoint}.
     *
     * @return false, recording nothing, when another endpoint already holds that port
     */
    public boolean bind(String accountId, String instanceId, int port, Endpoint endpoint) {
        Endpoint existing = endpoints.putIfAbsent(new Key(accountId, instanceId, port), endpoint);
        return existing == null || existing.equals(endpoint);
    }

    /** Releases {@code instanceId}'s {@code port} if {@code endpoint} still holds it. */
    public void release(String accountId, String instanceId, int port, Endpoint endpoint) {
        endpoints.remove(new Key(accountId, instanceId, port), endpoint);
    }

    public boolean isBound(String accountId, String instanceId, int port) {
        return endpoints.containsKey(new Key(accountId, instanceId, port));
    }

    public Optional<Endpoint> find(String accountId, String instanceId, int port) {
        if (accountId == null || instanceId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(endpoints.get(new Key(accountId, instanceId, port)));
    }

    @Override
    public void clear() {
        endpoints.clear();
    }
}
