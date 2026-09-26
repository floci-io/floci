package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.RouteTable;

/**
 * Listener notified when route tables are updated in EC2 (e.g. routes created, replaced,
 * deleted, or associated).
 */
public interface VpcRouteTableListener {
    void onRouteTableUpdated(String region, RouteTable routeTable);
}
