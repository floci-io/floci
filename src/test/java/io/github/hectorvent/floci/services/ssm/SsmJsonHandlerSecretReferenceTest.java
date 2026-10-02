package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SsmJsonHandlerSecretReferenceTest {

    private static final String REGION = "us-east-1";
    private static final String NAME = "/aws/reference/secretsmanager/app";
    private static final String CHECKED_ARN = "arn:aws:secretsmanager:us-east-1:000000000000:secret:app-AAAAAA";
    private static final String REPLACEMENT_ARN = "arn:aws:secretsmanager:us-east-1:000000000000:secret:app-BBBBBB";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aSecretReplacedBetweenTheCheckAndTheReadIsCheckedOnItsOwnArn() {
        SsmService ssmService = mock(SsmService.class);
        IamEnforcementFilter iamEnforcementFilter = mock(IamEnforcementFilter.class);
        Parameter replacement = new Parameter(NAME, "replacement-value", "SecureString");
        replacement.setArn(REPLACEMENT_ARN);
        when(ssmService.secretReferenceArn(NAME, REGION)).thenReturn(CHECKED_ARN);
        when(ssmService.getParameter(NAME, true, REGION)).thenReturn(replacement);
        when(ssmService.getParameters(List.of(NAME), true, REGION)).thenReturn(List.of(replacement));
        doThrow(new AwsException("AccessDenied", "denied", 403)).when(iamEnforcementFilter)
            .authorizeAdditionalResource("auth", "secretsmanager:GetSecretValue", REPLACEMENT_ARN);
        SsmJsonHandler handler = new SsmJsonHandler(ssmService, null, objectMapper, iamEnforcementFilter);

        ObjectNode getParameter = objectMapper.createObjectNode().put("Name", NAME).put("WithDecryption", true);
        ObjectNode getParameters = objectMapper.createObjectNode().put("WithDecryption", true);
        getParameters.putArray("Names").add(NAME);

        assertEquals("An error occurred while calling one AWS dependency service.", assertThrows(AwsException.class,
            () -> handler.handle("GetParameter", getParameter, REGION, "auth")).getMessage());
        assertEquals("An error occurred while calling one AWS dependency service.", assertThrows(AwsException.class,
            () -> handler.handle("GetParameters", getParameters, REGION, "auth")).getMessage());
    }
}
