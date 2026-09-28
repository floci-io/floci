package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-integration ids carry the state machine's partition, which the CDK builds as
 * {@code arn:${Aws.PARTITION}:states:::service:api} ({@code task-utils.ts}); the executor used
 * to compare against literal {@code arn:aws:} strings and silently failed to dispatch every
 * Task of a China or GovCloud state machine.
 */
class StatesIntegrationTest {

    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")
    void optimizedAndSdkIdsParseInEveryPartition(PartitionCase partitionCase) {
        String prefix = "arn:" + partitionCase.partition() + ":states:::";
        StatesIntegration lambda = StatesIntegration.parse(prefix + "lambda:invoke").orElseThrow();
        assertEquals(partitionCase.partition(), lambda.partition());
        assertTrue(lambda.is("lambda", "invoke"));
        assertFalse(lambda.isSdk("lambda", "invoke"));
        assertEquals("", lambda.suffix());
        assertEquals(prefix + "lambda:invoke", lambda.toString());

        StatesIntegration sdk = StatesIntegration.parse(prefix + "aws-sdk:dynamodb:putItem").orElseThrow();
        assertTrue(sdk.sdk());
        assertTrue(sdk.isSdk("dynamodb", "putItem"));
        assertTrue(sdk.isSdkService("dynamodb"));
        assertFalse(sdk.isOptimizedService("dynamodb"));
        assertEquals("putItem", sdk.api());
        assertEquals(prefix + "aws-sdk:dynamodb:putItem", sdk.withoutSuffix());
    }

    @ParameterizedTest
    @CsvSource({
            "arn:aws:states:::states:startExecution.sync,           states, startExecution, .sync",
            "arn:aws-cn:states:::ecs:runTask.sync:2,                ecs,    runTask,        .sync:2",
            "arn:aws-us-gov:states:::sqs:sendMessage.waitForTaskToken, sqs, sendMessage,    .waitForTaskToken",
            "arn:aws-iso:states:::s3:listObjectsV2,                 s3,     listObjectsV2,  ''"})
    void suffixesArePreservedButNeverPartOfTheApi(String resource, String service, String api, String suffix) {
        StatesIntegration integration = StatesIntegration.parse(resource).orElseThrow();
        assertEquals(service, integration.service());
        assertEquals(api, integration.api());
        assertEquals(suffix, integration.suffix());
        assertTrue(integration.isAnySuffix(service, api));
        assertEquals(suffix.isEmpty(), integration.is(service, api), "is() is exact: a suffixed id is another integration");
        assertEquals(resource, integration.toString());
    }

    /** Only AWS's three suffixes exist; anything else is not an integration id, as LocalStack refuses it too. */
    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:states:::sqs:sendMessage.bogus",
            "arn:aws:states:::ecs:runTask.sync:3",
            "arn:aws:states:::s3:getObject.waitForTaskToken.sync",
            "arn:aws:states:::aws-sdk:sqs:sendMessage.Sync"})
    void unknownSuffixesDoNotParse(String resource) {
        assertTrue(StatesIntegration.parse(resource).isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "arn:aws:states:us-east-1:000000000000:activity:work",
            "arn:aws:lambda:us-east-1:000000000000:function:f",
            "arn:aws:states:::lambda",
            "arn:aws:states:::Lambda:invoke",
            "arn:aws:states:::lambda:invoke:extra",
            "states:::lambda:invoke",
            "arn:mars:states:::lambda:invoke"})
    void activitiesPlainArnsAndMalformedIdsDoNotParse(String resource) {
        assertTrue(StatesIntegration.parse(resource).isEmpty());
    }

    @Test
    void tailIsEverythingAfterStatesInAnyPartition() {
        assertEquals("aws-sdk:sfn:startExecution", StatesIntegration.tail("arn:aws-cn:states:::aws-sdk:sfn:startExecution").orElseThrow());
        assertEquals("oddity", StatesIntegration.tail("arn:aws:states:::oddity").orElseThrow());
        assertTrue(StatesIntegration.tail("arn:aws:states:us-east-1:000000000000:activity:work").isEmpty());
        assertTrue(StatesIntegration.tail(null).isEmpty());
    }
}
