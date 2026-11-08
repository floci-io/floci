package io.floci.aws.services.stepfunctions;

import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StepFunctionsApiGatewayIamIntegrationTest extends StepFunctionsApiGatewayIntegrationTest {
}
