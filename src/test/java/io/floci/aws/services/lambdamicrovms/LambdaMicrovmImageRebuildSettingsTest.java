package io.floci.aws.services.lambdamicrovms;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class LambdaMicrovmImageRebuildSettingsTest {

    @Test
    void aRebuildRetainsTheOriginalRequestAsAWhole() {
        LambdaMicrovmsService service = new LambdaMicrovmsService(
                mock(RegionResolver.class), mock(StorageFactory.class));
        LambdaMicrovmsService.RuntimeSettings original = new LambdaMicrovmsService.RuntimeSettings(
                List.of("original-connector"), "/original", false,
                Map.of("STAGE", "one"), List.of("ALL"), List.of("ARM_64"));
        LambdaMicrovmsService.RuntimeSettings replacement = new LambdaMicrovmsService.RuntimeSettings(
                List.of("replacement-connector"), null, true,
                Map.of("STAGE", "two"), List.of(), List.of("X86_64"));
        service.createImage("us-east-1", "000000000000", "rebuild-settings",
                "original-base", "original-role", "s3://bucket/original.zip", "original description",
                "9", original, Map.of("owner", "original"));

        LambdaMicrovmsService.MicrovmImage rebuilt = service.createImage(
                "us-east-1", "000000000000", "rebuild-settings",
                "replacement-base", "replacement-role", "s3://bucket/replacement.zip", "replacement description",
                "10", replacement, Map.of("owner", "replacement", "new", "tag"));

        assertAll(
                () -> assertEquals(2, rebuilt.versions.size()),
                () -> assertEquals("original-base", rebuilt.baseImageArn),
                () -> assertEquals("original-role", rebuilt.buildRoleArn),
                () -> assertEquals("s3://bucket/original.zip", rebuilt.codeArtifactUri),
                () -> assertEquals("original description", rebuilt.description),
                () -> assertEquals("9", rebuilt.baseImageVersion),
                () -> assertEquals(original, rebuilt.runtimeSettings),
                () -> assertEquals(original, rebuilt.versions.get(0).runtimeSettings),
                () -> assertEquals(original, rebuilt.versions.get(1).runtimeSettings),
                () -> assertEquals(Map.of("owner", "original"), rebuilt.tags));
    }
}
