package io.github.hectorvent.floci.testing;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link SidecarContainersProfile} with S3 authorization enforced, so the floci-duck transfer of a
 * Spectrum cold load has to present a signed role session instead of the account-scoped development
 * credentials.
 */
public class SidecarS3AuthEnforcedProfile extends SidecarContainersProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("floci.services.s3.enforce-auth", "true");
        return overrides;
    }
}
