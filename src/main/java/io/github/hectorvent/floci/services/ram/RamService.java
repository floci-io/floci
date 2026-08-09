package io.github.hectorvent.floci.services.ram;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * AWS Resource Access Manager (RAM) business logic.
 *
 * <p>Currently covers only {@code EnableSharingWithAwsOrganization}: the flag flips and stays
 * enabled, matching the real service where the operation is idempotent and there is no disable
 * counterpart. Resource shares are not modeled yet.
 */
@ApplicationScoped
public class RamService {

    private volatile boolean sharingWithOrganizationEnabled;

    public boolean enableSharingWithAwsOrganization() {
        sharingWithOrganizationEnabled = true;
        return true;
    }

    public boolean isSharingWithOrganizationEnabled() {
        return sharingWithOrganizationEnabled;
    }
}
