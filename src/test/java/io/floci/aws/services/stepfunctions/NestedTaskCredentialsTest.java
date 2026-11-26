package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class NestedTaskCredentialsTest {
    @ParameterizedTest
    @ValueSource(strings = {"arn:aws:states:::http:invoke", "arn:aws:states:us-east-1:000000000111:activity:work",
            "arn:aws:sqs:us-east-1:000000000111:queue"})
    void aTaskThatIsNotALambdaOrServiceIntegrationFailsBeforeRoleLookup(String resource) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        IamService iam = mock(IamService.class);
        LambdaTaskAuthorizer authorizer = mock(LambdaTaskAuthorizer.class);
        NestedTaskCredentials credentials = new NestedTaskCredentials(iam, config, authorizer);
        assertThrows(AslExecutor.FailStateException.class,
                () -> credentials.account(new ObjectMapper().createObjectNode().put(
                        "RoleArn", "arn:aws:iam::000000000222:role/target"),
                        resource, new ObjectMapper().createObjectNode(), new StateMachine()));
        verifyNoInteractions(iam, authorizer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"arn:aws:states:::lambda:invoke", "arn:aws:lambda:us-east-1:000000000111:function:f",
            "arn:aws:states:::sqs:sendMessage.waitForTaskToken", "arn:aws:states:::aws-sdk:sfn:sendTaskSuccess",
            "arn:aws:states:::states:startExecution.sync:2", "arn:aws-cn:states:::dynamodb:putItem"})
    void lambdaAndServiceTasksRunInTheirRolesAccount(String resource) {
        assertTrue(NestedTaskCredentials.routes(resource));
    }

    @Test
    void anActivityAndAnHttpTaskKeepTheMachinesAccount() {
        assertFalse(NestedTaskCredentials.routes("arn:aws:states:us-east-1:000000000111:activity:work"));
        assertFalse(NestedTaskCredentials.routes("arn:aws:states:::http:invoke"));
    }

    /** An API Gateway task reaches only an API of the state machine's account, whose lookup stays there. */
    @Test
    void anApiGatewayTaskKeepsTheMachinesAccount() {
        assertFalse(NestedTaskCredentials.routes("arn:aws:states:::apigateway:invoke"));
    }

    @Test
    void theMachineInAnotherAccountKeepsItsNameRegionAndRole() {
        StateMachine machine = new StateMachine();
        machine.setStateMachineArn("arn:aws:states:eu-west-1:000000000111:stateMachine:m");
        machine.setRoleArn("arn:aws:iam::000000000111:role/execution");

        StateMachine view = NestedTaskCredentials.inAccount(machine, "000000000222");

        assertEquals("arn:aws:states:eu-west-1:000000000222:stateMachine:m", view.getStateMachineArn());
        assertEquals(machine.getRoleArn(), view.getRoleArn());
        assertSame(machine, NestedTaskCredentials.inAccount(machine, null));
        assertSame(machine, NestedTaskCredentials.inAccount(machine, "000000000111"));
    }
}
