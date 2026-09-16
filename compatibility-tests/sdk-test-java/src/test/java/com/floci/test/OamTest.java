package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.oam.OamClient;
import software.amazon.awssdk.services.oam.model.CreateSinkRequest;
import software.amazon.awssdk.services.oam.model.DeleteSinkRequest;
import software.amazon.awssdk.services.oam.model.GetSinkRequest;
import software.amazon.awssdk.services.oam.model.ListSinksRequest;

import static org.junit.jupiter.api.Assertions.*;

class OamTest {
    @Test
    void sinkLifecycle() {
        try (OamClient client = TestFixtures.oamClient()) {
            var created = client.createSink(CreateSinkRequest.builder().name("sdk-oam-sink").build());
            assertNotNull(created.arn());
            assertEquals("sdk-oam-sink", created.name());

            var fetched = client.getSink(GetSinkRequest.builder().identifier(created.arn()).build());
            assertEquals(created.arn(), fetched.arn());
            assertTrue(client.listSinks(ListSinksRequest.builder().build()).items().stream()
                    .anyMatch(item -> created.arn().equals(item.arn())));

            client.deleteSink(DeleteSinkRequest.builder().identifier(created.arn()).build());
        }
    }
}
