package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ImageResolverTest {

    private final ImageResolver resolver = new ImageResolver();

    @ParameterizedTest
    @CsvSource({
            "java21, public.ecr.aws/lambda/java:21",
            "java17, public.ecr.aws/lambda/java:17",
            "java11, public.ecr.aws/lambda/java:11",
            "python3.13, public.ecr.aws/lambda/python:3.13",
            "python3.12, public.ecr.aws/lambda/python:3.12",
            "python3.11, public.ecr.aws/lambda/python:3.11",
            "python3.10, public.ecr.aws/lambda/python:3.10",
            "python3.9, public.ecr.aws/lambda/python:3.9",
            "nodejs22.x, public.ecr.aws/lambda/nodejs:22",
            "nodejs20.x, public.ecr.aws/lambda/nodejs:20",
            "nodejs18.x, public.ecr.aws/lambda/nodejs:18",
            "ruby3.3, public.ecr.aws/lambda/ruby:3.3",
            "ruby3.2, public.ecr.aws/lambda/ruby:3.2",
            "provided.al2023, public.ecr.aws/lambda/provided:al2023",
            "provided.al2, public.ecr.aws/lambda/provided:al2"
    })
    void resolvesKnownRuntimes(String runtime, String expectedImage) {
        assertEquals(expectedImage, resolver.resolve(runtime));
    }

    @Test
    void passesThroughCustomImageWithSlash() {
        String customImage = "123456789.dkr.ecr.us-east-1.amazonaws.com/my-function:latest";
        assertEquals(customImage, resolver.resolve(customImage));
    }

    @Test
    void passesThroughCustomImageWithColon() {
        String customImage = "myrepo:latest";
        assertEquals(customImage, resolver.resolve(customImage));
    }

    @Test
    void throwsForUnknownRuntime() {
        AwsException ex = assertThrows(AwsException.class, () -> resolver.resolve("dotnet8"));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
    }

    @Test
    void throwsForNullRuntime() {
        assertThrows(AwsException.class, () -> resolver.resolve(null));
    }

    @Test
    void throwsForBlankRuntime() {
        assertThrows(AwsException.class, () -> resolver.resolve("  "));
    }
}
