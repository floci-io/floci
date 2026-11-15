package io.floci.aws.testing;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link SidecarContainersProfile} with Lake Formation enforced on Spectrum queries, so an external
 * table is readable only by a role that holds a Lake Formation {@code SELECT} grant on it.
 */
public class SidecarSpectrumLakeFormationProfile extends SidecarContainersProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("floci.services.redshift.spectrum-lake-formation-enforcement", "true");
        return overrides;
    }
}
