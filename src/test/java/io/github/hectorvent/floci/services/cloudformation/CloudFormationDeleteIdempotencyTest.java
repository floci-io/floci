package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CloudFormationDeleteIdempotencyTest {

    private static final String REGION = "us-east-1";

    private DynamoDbService dynamoDbService;
    private LambdaService lambdaService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        dynamoDbService = mock(DynamoDbService.class);
        lambdaService = mock(LambdaService.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, dynamoDbService, lambdaService,
                null, null, null, null, null,
                null, null, null, null, null, null,
                new ObjectMapper(),
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                new CloudFormationResourceRegistry(List.of()));
    }

    @Test
    void missingDynamoDbTableIsAlreadyDeleted() {
        doThrow(awsError("ResourceNotFoundException"))
                .when(dynamoDbService).deleteTable("missing-table", REGION);

        assertDoesNotThrow(() -> provisioner.delete("AWS::DynamoDB::Table", "missing-table", REGION));

        verify(dynamoDbService).deleteTable("missing-table", REGION);
    }

    @Test
    void missingLambdaFunctionIsAlreadyDeleted() {
        doThrow(awsError("ResourceNotFoundException"))
                .when(lambdaService).deleteFunction(REGION, "missing-function");

        assertDoesNotThrow(() -> provisioner.delete("AWS::Lambda::Function", "missing-function", REGION));

        verify(lambdaService).deleteFunction(REGION, "missing-function");
    }

    @Test
    void dynamoDbDeleteFailureStillPropagates() {
        AwsException expected = awsError("InternalServerError");
        doThrow(expected).when(dynamoDbService).deleteTable("table", REGION);

        AwsException actual = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::DynamoDB::Table", "table", REGION));

        assertSame(expected, actual);
    }

    @Test
    void lambdaDeleteFailureStillPropagates() {
        AwsException expected = awsError("ServiceException");
        doThrow(expected).when(lambdaService).deleteFunction(REGION, "function");

        AwsException actual = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::Lambda::Function", "function", REGION));

        assertSame(expected, actual);
    }

    private static AwsException awsError(String errorCode) {
        return new AwsException(errorCode, "delete failed", 500);
    }
}
