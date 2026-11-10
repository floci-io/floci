package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class NestedTaskCredentialsTest {
    @ParameterizedTest
    @ValueSource(strings = {"lambda:invoke", "states:startExecution.waitForTaskToken", "aws-sdk:sfn:sendTaskSuccess"})
    void unsupportedIntegrationFailsBeforeRoleLookup(String resource) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        IamService iam = mock(IamService.class);
        LambdaTaskAuthorizer authorizer = mock(LambdaTaskAuthorizer.class);
        NestedTaskCredentials credentials = new NestedTaskCredentials(iam, config, authorizer);
        assertThrows(AslExecutor.FailStateException.class,
                () -> credentials.account(new ObjectMapper().createObjectNode().put(
                        "RoleArn", "arn:aws:iam::000000000222:role/target"),
                        "arn:aws:states:::" + resource, new ObjectMapper().createObjectNode(), new StateMachine()));
        verifyNoInteractions(iam, authorizer);
    }
}
