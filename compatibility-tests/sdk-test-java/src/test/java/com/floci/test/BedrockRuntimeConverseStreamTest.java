package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Bedrock Runtime's ConverseStream against the real AWS SDK's event-stream decoder,
 * not just Floci's own raw-byte assertions - proves the {@code application/vnd.amazon.eventstream}
 * frames Floci emits actually round-trip through {@link BedrockRuntimeAsyncClient}'s generated
 * unmarshaller end to end.
 */
@DisplayName("Bedrock Runtime ConverseStream")
class BedrockRuntimeConverseStreamTest {

    private static final String MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0";

    @Test
    void converseStream_deliversTextDeltasAndCompletes() throws Exception {
        try (BedrockRuntimeAsyncClient client = TestFixtures.bedrockRuntimeAsyncClient()) {
            List<ConverseStreamOutput> received = new ArrayList<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch complete = new CountDownLatch(1);

            ConverseStreamResponseHandler handler = ConverseStreamResponseHandler.builder()
                    .subscriber(received::add)
                    .onError(error::set)
                    .onComplete(complete::countDown)
                    .build();

            ConverseStreamRequest request = ConverseStreamRequest.builder()
                    .modelId(MODEL_ID)
                    .messages(Message.builder()
                            .role(ConversationRole.USER)
                            .content(List.of(ContentBlock.fromText("hi")))
                            .build())
                    .build();

            CompletableFuture<Void> future = client.converseStream(request, handler);
            future.get(15, TimeUnit.SECONDS);

            assertThat(complete.await(15, TimeUnit.SECONDS)).as("onComplete should fire").isTrue();
            assertThat(error.get()).as("no stream error expected").isNull();
            assertThat(received).isNotEmpty();
            assertThat(received.get(0)).isInstanceOf(MessageStartEvent.class);

            List<String> text = received.stream()
                    .filter(ContentBlockDeltaEvent.class::isInstance)
                    .map(ContentBlockDeltaEvent.class::cast)
                    .map(ContentBlockDeltaEvent::delta)
                    .map(ContentBlockDelta::text)
                    .toList();
            assertThat(text)
                    .containsExactly("Floci stub response", " for model=", MODEL_ID);

            assertThat(received).anyMatch(MessageStopEvent.class::isInstance);
        }
    }
}
