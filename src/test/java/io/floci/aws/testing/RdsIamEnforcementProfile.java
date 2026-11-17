package io.floci.aws.testing;

import java.util.HashMap;
import java.util.Map;

/** Real control-plane authorization with only the database container runtime mocked. */
public class RdsIamEnforcementProfile extends IamEnforcementProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("floci.services.rds.mock", "true");
        overrides.put("floci.services.docdb.mock", "true");
        return overrides;
    }
}
