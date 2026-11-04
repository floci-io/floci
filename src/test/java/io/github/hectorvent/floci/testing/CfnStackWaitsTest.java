package io.github.hectorvent.floci.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CfnStackWaitsTest {

    private static final String NAME = "waiter-stack";
    private static final DescribeStacksRequest REQUEST = DescribeStacksRequest.builder().stackName(NAME).build();
    private final CloudFormationClient client = mock(CloudFormationClient.class);

    @Test
    void sdkWaitReturnsTheCompleteStackAfterProgress() {
        Stack completed = Stack.builder().stackName(NAME).stackStatus("CREATE_COMPLETE")
                .outputs(output -> output.outputKey("Bucket").outputValue("created-bucket")).build();
        when(client.describeStacks(REQUEST)).thenReturn(
                response(Stack.builder().stackStatus("CREATE_IN_PROGRESS").build()), response(completed));

        assertSame(completed, CfnStackWaits.awaitStatus(client, NAME, "CREATE_COMPLETE"));
        verify(client, times(2)).describeStacks(REQUEST);
    }

    @Test
    void sdkWaitAcceptsAnExpectedRollback() {
        Stack rollback = Stack.builder().stackStatus("UPDATE_ROLLBACK_COMPLETE")
                .stackStatusReason("Resource update failed").build();
        when(client.describeStacks(REQUEST)).thenReturn(response(rollback));

        assertSame(rollback, CfnStackWaits.awaitStatus(client, NAME, "UPDATE_ROLLBACK_COMPLETE"));
        verify(client).describeStacks(REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE_FAILED", "ROLLBACK_COMPLETE", "ROLLBACK_FAILED", "UPDATE_COMPLETE",
            "UPDATE_FAILED", "UPDATE_ROLLBACK_COMPLETE", "UPDATE_ROLLBACK_FAILED", "DELETE_COMPLETE", "DELETE_FAILED"})
    void sdkWaitRejectsAnyOtherTerminalStatusWithoutPollingAgain(String status) {
        Stack terminal = Stack.builder().stackStatus(status).stackStatusReason("Resource rejected").build();
        Stack laterSuccess = Stack.builder().stackStatus("CREATE_COMPLETE").build();
        when(client.describeStacks(REQUEST)).thenReturn(response(terminal), response(laterSuccess));

        AssertionError failure = assertThrows(AssertionError.class,
                () -> CfnStackWaits.awaitStatus(client, NAME, "CREATE_COMPLETE"));

        assertEquals("Stack " + NAME + " reached " + status + " instead of CREATE_COMPLETE: Resource rejected",
                failure.getMessage());
        verify(client).describeStacks(REQUEST);
    }

    @Test
    void sdkWaitPropagatesDescribeErrorsWithoutPollingAgain() {
        AwsServiceException error = CloudFormationException.builder().statusCode(400)
                .message("Stack does not exist").build();
        when(client.describeStacks(REQUEST)).thenThrow(error);

        assertSame(error, assertThrows(CloudFormationException.class,
                () -> CfnStackWaits.awaitStatus(client, NAME, "CREATE_COMPLETE")));
        verify(client).describeStacks(REQUEST);
    }

    @Test
    void sdkWaitRejectsAnEmptyStackResponseWithoutPollingAgain() {
        when(client.describeStacks(REQUEST)).thenReturn(DescribeStacksResponse.builder().build());

        AssertionError failure = assertThrows(AssertionError.class,
                () -> CfnStackWaits.awaitStatus(client, NAME, "CREATE_COMPLETE"));

        assertEquals("DescribeStacks " + NAME + " returned no stacks", failure.getMessage());
        verify(client).describeStacks(REQUEST);
    }

    private static DescribeStacksResponse response(Stack stack) {
        return DescribeStacksResponse.builder().stacks(stack).build();
    }
}
