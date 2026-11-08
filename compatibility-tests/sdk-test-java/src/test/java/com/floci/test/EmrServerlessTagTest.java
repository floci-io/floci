package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.emrserverless.EmrServerlessClient;
import software.amazon.awssdk.services.emrserverless.model.CreateApplicationResponse;
import software.amazon.awssdk.services.emrserverless.model.ResourceNotFoundException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EMR Serverless tags")
class EmrServerlessTagTest {

    private static EmrServerlessClient emr;
    private static String applicationId;
    private static String arn;

    @BeforeAll
    static void setup() {
        emr = TestFixtures.emrServerlessClient();
        CreateApplicationResponse created = emr.createApplication(r -> r
                .name(TestFixtures.uniqueName("tagged-app"))
                .releaseLabel("emr-7.5.0")
                .type("SPARK")
                .clientToken(UUID.randomUUID().toString())
                .tags(Map.of("team", "data")));
        applicationId = created.applicationId();
        arn = created.arn();
    }

    @AfterAll
    static void cleanup() {
        if (emr == null) {
            return;
        }
        try {
            emr.deleteApplication(r -> r.applicationId(applicationId));
        }
        catch (Exception ignored) {
            // Cleanup is best effort: the application is unique to this run and the emulator is disposable.
        }
        emr.close();
    }

    @Test
    @DisplayName("TagResource, UntagResource and ListTagsForResource round trip on the application ARN")
    void tagRoundTrip() {
        assertThat(emr.listTagsForResource(r -> r.resourceArn(arn)).tags()).containsExactly(Map.entry("team", "data"));

        emr.tagResource(r -> r.resourceArn(arn).tags(Map.of("env", "dev", "team", "platform")));
        assertThat(emr.listTagsForResource(r -> r.resourceArn(arn)).tags())
                .containsOnly(Map.entry("team", "platform"), Map.entry("env", "dev"));
        assertThat(emr.getApplication(r -> r.applicationId(applicationId)).application().tags())
                .containsEntry("env", "dev");

        emr.untagResource(r -> r.resourceArn(arn).tagKeys("env"));
        assertThat(emr.listTagsForResource(r -> r.resourceArn(arn)).tags()).containsOnly(Map.entry("team", "platform"));
    }

    @Test
    @DisplayName("An unknown application is ResourceNotFoundException")
    void unknownApplication() {
        String missing = arn.substring(0, arn.lastIndexOf('/') + 1) + "00missing00";
        assertThatThrownBy(() -> emr.listTagsForResource(r -> r.resourceArn(missing)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
