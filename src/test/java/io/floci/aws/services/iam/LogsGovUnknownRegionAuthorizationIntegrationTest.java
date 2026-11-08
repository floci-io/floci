package io.floci.aws.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(LogsNoncommercialUnknownRegionAuthorizationIntegrationTest.GovProfile.class)
class LogsGovUnknownRegionAuthorizationIntegrationTest extends LogsNoncommercialUnknownRegionAuthorizationIntegrationTest {
    @Override
    String deploymentPartition() {
        return "aws-us-gov";
    }
}
