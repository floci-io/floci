package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.pipes.PipesClient;
import software.amazon.awssdk.services.pipes.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EventBridge Pipes")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PipesTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/pipe-role";

    private static PipesClient pipes;
    private static SqsClient sqs;
    private static String pipeName;
    private static String srcQueue;
    private static String tgtQueue;
    private static String srcQueueUrl;
    private static String tgtQueueUrl;

    private static String sqsArn(String queueName) {
        return "arn:aws:sqs:" + REGION + ":" + ACCOUNT_ID + ":" + queueName;
    }

    @BeforeAll
    static void setup() {
        pipes = TestFixtures.pipesClient();
        sqs = TestFixtures.sqsClient();
        pipeName = TestFixtures.uniqueName("pipe");
        srcQueue = TestFixtures.uniqueName("pipe-src");
        tgtQueue = TestFixtures.uniqueName("pipe-tgt");

        srcQueueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(srcQueue).build()).queueUrl();
        tgtQueueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(tgtQueue).build()).queueUrl();
    }

    @AfterAll
    static void cleanup() {
        if (pipes != null) {
            try { pipes.deletePipe(DeletePipeRequest.builder().name(pipeName).build()); } catch (Exception ignored) {}
            pipes.close();
        }
        if (sqs != null) {
            try { sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(srcQueueUrl).build()); } catch (Exception ignored) {}
            try { sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(tgtQueueUrl).build()); } catch (Exception ignored) {}
            sqs.close();
        }
    }

    @Test
    @Order(1)
    void createPipe() {
        CreatePipeResponse response = pipes.createPipe(CreatePipeRequest.builder()
                .name(pipeName)
                .source(sqsArn(srcQueue))
                .target(sqsArn(tgtQueue))
                .roleArn(ROLE_ARN)
                .desiredState(RequestedPipeState.STOPPED)
                .build());

        assertThat(response.currentState()).isEqualTo(PipeState.STOPPED);
        assertThat(response.arn()).contains(pipeName);
    }

    @Test
    @Order(2)
    void describePipe() {
        DescribePipeResponse response = pipes.describePipe(DescribePipeRequest.builder()
                .name(pipeName).build());

        assertThat(response.name()).isEqualTo(pipeName);
        assertThat(response.source()).isEqualTo(sqsArn(srcQueue));
        assertThat(response.target()).isEqualTo(sqsArn(tgtQueue));
        assertThat(response.currentState()).isEqualTo(PipeState.STOPPED);
    }

    @Test
    @Order(3)
    void listPipes() {
        ListPipesResponse response = pipes.listPipes(ListPipesRequest.builder().build());

        assertThat(response.pipes())
                .anyMatch(p -> pipeName.equals(p.name()));
    }

    @Test
    @Order(4)
    void updatePipe() {
        pipes.updatePipe(UpdatePipeRequest.builder()
                .name(pipeName)
                .roleArn(ROLE_ARN)
                .description("updated via SDK")
                .desiredState(RequestedPipeState.STOPPED)
                .build());

        DescribePipeResponse response = pipes.describePipe(DescribePipeRequest.builder()
                .name(pipeName).build());

        assertThat(response.description()).isEqualTo("updated via SDK");
    }

    @Test
    @Order(5)
    void startAndStopPipe() {
        StartPipeResponse startResponse = pipes.startPipe(StartPipeRequest.builder()
                .name(pipeName).build());
        assertThat(startResponse.currentState()).isEqualTo(PipeState.RUNNING);

        StopPipeResponse stopResponse = pipes.stopPipe(StopPipeRequest.builder()
                .name(pipeName).build());
        assertThat(stopResponse.currentState()).isEqualTo(PipeState.STOPPED);
    }

    @Test
    @Order(6)
    void deletePipe() {
        pipes.deletePipe(DeletePipeRequest.builder().name(pipeName).build());

        assertThatThrownBy(() -> pipes.describePipe(DescribePipeRequest.builder()
                .name(pipeName).build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(7)
    void describeNonExistentPipe() {
        assertThatThrownBy(() -> pipes.describePipe(DescribePipeRequest.builder()
                .name("nonexistent-pipe").build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(10)
    void sqsToSqsForwarding() throws InterruptedException {
        String fwdPipeName = TestFixtures.uniqueName("pipe-fwd");
        String fwdSrc = TestFixtures.uniqueName("pipe-fwd-src");
        String fwdTgt = TestFixtures.uniqueName("pipe-fwd-tgt");
        String fwdSrcUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(fwdSrc).build()).queueUrl();
        String fwdTgtUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(fwdTgt).build()).queueUrl();

        try {
            pipes.createPipe(CreatePipeRequest.builder()
                    .name(fwdPipeName)
                    .source(sqsArn(fwdSrc))
                    .target(sqsArn(fwdTgt))
                    .roleArn(ROLE_ARN)
                    .desiredState(RequestedPipeState.RUNNING)
                    .build());

            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(fwdSrcUrl)
                    .messageBody("hello from pipes")
                    .build());

            boolean found = false;
            for (int i = 0; i < 15; i++) {
                Thread.sleep(1000);
                ReceiveMessageResponse recv = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(fwdTgtUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(1)
                        .build());
                if (!recv.messages().isEmpty()
                        && recv.messages().get(0).body().contains("hello from pipes")) {
                    found = true;
                    break;
                }
            }
            assertThat(found).as("target queue should receive forwarded message").isTrue();
        } finally {
            try { pipes.deletePipe(DeletePipeRequest.builder().name(fwdPipeName).build()); } catch (Exception ignored) {}
            try { sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(fwdSrcUrl).build()); } catch (Exception ignored) {}
            try { sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(fwdTgtUrl).build()); } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(11)
    void stoppedPipeDoesNotForward() throws InterruptedException {
        String nfPipeName = TestFixtures.uniqueName("pipe-nofwd");
        String nfSrc = TestFixtures.uniqueName("pipe-nofwd-src");
        String nfTgt = TestFixtures.uniqueName("pipe-nofwd-tgt");
        String nfSrcUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(nfSrc).build()).queueUrl();
        String nfTgtUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(nfTgt).build()).queueUrl();

        try {
            pipes.createPipe(CreatePipeRequest.builder()
                    .name(nfPipeName)
                    .source(sqsArn(nfSrc))
                    .target(sqsArn(nfTgt))
                    .roleArn(ROLE_ARN)
                    .desiredState(RequestedPipeState.STOPPED)
                    .build());

            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(nfSrcUrl)
                    .messageBody("should not forward")
                    .build());

            Thread.sleep(3000);

            ReceiveMessageResponse recv = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(nfTgtUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(1)
                    .build());
            assertThat(recv.messages()).isEmpty();
        } finally {
            try { pipes.deletePipe(DeletePipeRequest.builder().name(nfPipeName).build()); } catch (Exception ignored) {}
            try { sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(nfSrcUrl).build()); } catch (Exception ignored) {}
            try { sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(nfTgtUrl).build()); } catch (Exception ignored) {}
        }
    }
}
