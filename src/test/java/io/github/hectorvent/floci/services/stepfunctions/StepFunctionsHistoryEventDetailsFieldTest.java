package io.github.hectorvent.floci.services.stepfunctions;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepFunctionsHistoryEventDetailsFieldTest {

    @ParameterizedTest
    @CsvSource({
            "ActivityFailed, activityFailedEventDetails",
            "ActivityScheduleFailed, activityScheduleFailedEventDetails",
            "ActivityScheduled, activityScheduledEventDetails",
            "ActivityStarted, activityStartedEventDetails",
            "ActivitySucceeded, activitySucceededEventDetails",
            "ActivityTimedOut, activityTimedOutEventDetails",
            "TaskFailed, taskFailedEventDetails",
            "TaskScheduled, taskScheduledEventDetails",
            "TaskStartFailed, taskStartFailedEventDetails",
            "TaskStarted, taskStartedEventDetails",
            "TaskSubmitFailed, taskSubmitFailedEventDetails",
            "TaskSubmitted, taskSubmittedEventDetails",
            "TaskSucceeded, taskSucceededEventDetails",
            "TaskTimedOut, taskTimedOutEventDetails",
            "ExecutionFailed, executionFailedEventDetails",
            "ExecutionStarted, executionStartedEventDetails",
            "ExecutionSucceeded, executionSucceededEventDetails",
            "ExecutionAborted, executionAbortedEventDetails",
            "ExecutionTimedOut, executionTimedOutEventDetails",
            "ExecutionRedriven, executionRedrivenEventDetails",
            "MapStateStarted, mapStateStartedEventDetails",
            "MapIterationStarted, mapIterationStartedEventDetails",
            "MapIterationSucceeded, mapIterationSucceededEventDetails",
            "MapIterationFailed, mapIterationFailedEventDetails",
            "MapIterationAborted, mapIterationAbortedEventDetails",
            "LambdaFunctionFailed, lambdaFunctionFailedEventDetails",
            "LambdaFunctionScheduleFailed, lambdaFunctionScheduleFailedEventDetails",
            "LambdaFunctionScheduled, lambdaFunctionScheduledEventDetails",
            "LambdaFunctionStartFailed, lambdaFunctionStartFailedEventDetails",
            "LambdaFunctionSucceeded, lambdaFunctionSucceededEventDetails",
            "LambdaFunctionTimedOut, lambdaFunctionTimedOutEventDetails",
            "MapRunStarted, mapRunStartedEventDetails",
            "MapRunFailed, mapRunFailedEventDetails",
            "MapRunRedriven, mapRunRedrivenEventDetails",
            "EvaluationFailed, evaluationFailedEventDetails",
            "PassStateEntered, stateEnteredEventDetails",
            "TaskStateEntered, stateEnteredEventDetails",
            "ChoiceStateEntered, stateEnteredEventDetails",
            "WaitStateEntered, stateEnteredEventDetails",
            "SucceedStateEntered, stateEnteredEventDetails",
            "FailStateEntered, stateEnteredEventDetails",
            "ParallelStateEntered, stateEnteredEventDetails",
            "MapStateEntered, stateEnteredEventDetails",
            "PassStateExited, stateExitedEventDetails",
            "TaskStateExited, stateExitedEventDetails",
            "ChoiceStateExited, stateExitedEventDetails",
            "WaitStateExited, stateExitedEventDetails",
            "SucceedStateExited, stateExitedEventDetails",
            "FailStateExited, stateExitedEventDetails",
            "ParallelStateExited, stateExitedEventDetails",
            "MapStateExited, stateExitedEventDetails"
    })
    void historyEventDetailsField_matchesAwsSdkFieldName(String type, String fieldName) {
        assertEquals(fieldName, StepFunctionsJsonHandler.historyEventDetailsField(type));
    }
}
