package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateFunctionResponse;
import software.amazon.awssdk.services.cloudfront.model.FunctionStage;
import software.amazon.awssdk.services.cloudfront.model.GetFunctionResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudFrontFunctionTest {

    @Test
    void getFunctionReturnsTheDecodedFunctionCode() {
        String name = "compat-function-" + TestFixtures.uniqueName();
        byte[] code = "function handler(event) { return event.request; }"
                .getBytes(StandardCharsets.UTF_8);
        byte[] updatedCode = "function handler(event) { return event.response; }"
                .getBytes(StandardCharsets.UTF_8);
        String etag = null;
        String updatedEtag = null;

        try (CloudFrontClient cloudFront = TestFixtures.cloudFrontClient()) {
            CreateFunctionResponse created = cloudFront.createFunction(request -> request
                    .name(name)
                    .functionConfig(config -> config
                            .comment("compat round trip")
                            .runtime("cloudfront-js-2.0"))
                    .functionCode(SdkBytes.fromByteArray(code)));
            String createEtag = created.eTag();
            etag = createEtag;

            GetFunctionResponse function = cloudFront.getFunction(request -> request
                    .name(name)
                    .stage(FunctionStage.DEVELOPMENT));
            assertArrayEquals(code, function.functionCode().asByteArray());
            assertEquals(createEtag, function.eTag());
            assertEquals("application/octet-stream", function.contentType());

            cloudFront.updateFunction(request -> request
                    .name(name)
                    .ifMatch(createEtag)
                    .functionConfig(config -> config
                            .comment("compat round trip")
                            .runtime("cloudfront-js-2.0"))
                    .functionCode(SdkBytes.fromByteArray(updatedCode)));

            GetFunctionResponse afterUpdate = cloudFront.getFunction(request -> request
                    .name(name)
                    .stage(FunctionStage.DEVELOPMENT));
            updatedEtag = afterUpdate.eTag();
            assertArrayEquals(updatedCode, afterUpdate.functionCode().asByteArray());
            assertEquals("application/octet-stream", afterUpdate.contentType());
        } finally {
            try (CloudFrontClient cloudFront = TestFixtures.cloudFrontClient()) {
                String currentEtag = updatedEtag != null ? updatedEtag : etag;
                if (currentEtag != null) {
                    cloudFront.deleteFunction(request -> request
                            .name(name)
                            .ifMatch(currentEtag));
                }
            }
        }
    }
}
