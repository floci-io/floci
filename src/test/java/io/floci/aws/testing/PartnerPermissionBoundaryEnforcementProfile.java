package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * A deployment that enforces IAM policies and has registered permissions boundaries for a
 * delegation partner, which is what it takes to see the condition key a delegated role write
 * publishes: without enforcement nothing reads it, and without a registration there is no cap to
 * publish.
 *
 * <p>Both overrides are inert for every class that does not ask for this profile, and
 * {@link PartnerPermissionBoundaryProfile} covers the cases that need the registration alone.
 */
public class PartnerPermissionBoundaryEnforcementProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.iam.enforcement-enabled", "true",
                "floci.services.iam.partner-permission-boundaries-path",
                "src/test/resources/partner-permission-boundaries.json");
    }
}
