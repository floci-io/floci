package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * A deployment that has registered permissions boundaries for a delegation partner, which AWS does
 * by email and this emulator reads from a file.
 *
 * <p>The one override, {@code floci.services.iam.partner-permission-boundaries-path}, names
 * {@code src/test/resources/partner-permission-boundaries.json}, which registers one read-only
 * boundary for partner account {@code 000000000001}. Unset, which every other profile leaves it,
 * registers nothing and a delegation request carries no boundary, so this is inert for every class
 * that does not ask for it.
 */
public class PartnerPermissionBoundaryProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.iam.partner-permission-boundaries-path",
                "src/test/resources/partner-permission-boundaries.json");
    }
}
