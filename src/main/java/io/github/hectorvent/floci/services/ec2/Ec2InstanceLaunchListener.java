package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;

/**
 * Listener notified when an EC2 instance is launched.
 */
public interface Ec2InstanceLaunchListener {

    void onInstanceLaunched(String accountId, String region, Instance instance);
}
