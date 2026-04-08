package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageResolverTest {

    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final ImageResolver resolver;

    ImageResolverTest() {
        when(config.ecrBaseUri()).thenReturn("public.ecr.aws");
        this.resolver = new ImageResolver(config);
    }

    @ParameterizedTest
    @CsvSource({
            "java25, public.ecr.aws/lambda/java:25",
            "java21, public.ecr.aws/lambda/java:21",
            "java17, public.ecr.aws/lambda/java:17",
            "java11, public.ecr.aws/lambda/java:11",
            "java8.al2, public.ecr.aws/lambda/java:8.al2",
            "java8, public.ecr.aws/lambda/java:8",
            "python3.14, public.ecr.aws/lambda/python:3.14",
            "python3.13, public.ecr.aws/lambda/python:3.13",
            "python3.12, public.ecr.aws/lambda/python:3.12",
            "python3.11, public.ecr.aws/lambda/python:3.11",
            "python3.10, public.ecr.aws/lambda/python:3.10",
            "python3.9, public.ecr.aws/lambda/python:3.9",
            "nodejs24.x, public.ecr.aws/lambda/nodejs:24",
            "nodejs22.x, public.ecr.aws/lambda/nodejs:22",
            "nodejs20.x, public.ecr.aws/lambda/nodejs:20",
            "nodejs18.x, public.ecr.aws/lambda/nodejs:18",
            "nodejs16.x, public.ecr.aws/lambda/nodejs:16",
            "ruby3.4, public.ecr.aws/lambda/ruby:3.4",
            "ruby3.3, public.ecr.aws/lambda/ruby:3.3",
            "ruby3.2, public.ecr.aws/lambda/ruby:3.2",
            "dotnet10, public.ecr.aws/lambda/dotnet:10",
            "dotnet9, public.ecr.aws/lambda/dotnet:9",
            "dotnet8, public.ecr.aws/lambda/dotnet:8",
            "dotnet6, public.ecr.aws/lambda/dotnet:6",
            "go1.x, public.ecr.aws/lambda/go:1",
            "provided.al2023, public.ecr.aws/lambda/provided:al2023",
            "provided.al2, public.ecr.aws/lambda/provided:al2",
            "provided, public.ecr.aws/lambda/provided:latest"
    })
    void resolvesKnownRuntimes(String runtime, String expectedImage) {
        assertEquals(expectedImage, resolver.resolve(runtime));
    }

    @ParameterizedTest
    @CsvSource({
            "java25, my.custom.host/lambda/java:25",
            "java21, my.custom.host/lambda/java:21",
            "java17, my.custom.host/lambda/java:17",
            "java11, my.custom.host/lambda/java:11",
            "java8.al2, my.custom.host/lambda/java:8.al2",
            "java8, my.custom.host/lambda/java:8",
            "python3.14, my.custom.host/lambda/python:3.14",
            "python3.13, my.custom.host/lambda/python:3.13",
            "python3.12, my.custom.host/lambda/python:3.12",
            "python3.11, my.custom.host/lambda/python:3.11",
            "python3.10, my.custom.host/lambda/python:3.10",
            "python3.9, my.custom.host/lambda/python:3.9",
            "nodejs24.x, my.custom.host/lambda/nodejs:24",
            "nodejs22.x, my.custom.host/lambda/nodejs:22",
            "nodejs20.x, my.custom.host/lambda/nodejs:20",
            "nodejs18.x, my.custom.host/lambda/nodejs:18",
            "nodejs16.x, my.custom.host/lambda/nodejs:16",
            "ruby3.4, my.custom.host/lambda/ruby:3.4",
            "ruby3.3, my.custom.host/lambda/ruby:3.3",
            "ruby3.2, my.custom.host/lambda/ruby:3.2",
            "dotnet10, my.custom.host/lambda/dotnet:10",
            "dotnet9, my.custom.host/lambda/dotnet:9",
            "dotnet8, my.custom.host/lambda/dotnet:8",
            "dotnet6, my.custom.host/lambda/dotnet:6",
            "go1.x, my.custom.host/lambda/go:1",
            "provided.al2023, my.custom.host/lambda/provided:al2023",
            "provided.al2, my.custom.host/lambda/provided:al2",
            "provided, my.custom.host/lambda/provided:latest"
    })
    void resolvesKnownRuntimesWithHostOverride(String runtime, String expectedImage) {
        EmulatorConfig customConfig = mock(EmulatorConfig.class);
        when(customConfig.ecrBaseUri()).thenReturn("my.custom.host");
        ImageResolver customResolver = new ImageResolver(customConfig);
        assertEquals(expectedImage, customResolver.resolve(runtime));
    }

    @ParameterizedTest
    @CsvSource({
            "java25, my.custom.host/path/lambda/java:25",
            "java21, my.custom.host/path/lambda/java:21",
            "java17, my.custom.host/path/lambda/java:17",
            "java11, my.custom.host/path/lambda/java:11",
            "java8.al2, my.custom.host/path/lambda/java:8.al2",
            "java8, my.custom.host/path/lambda/java:8",
            "python3.14, my.custom.host/path/lambda/python:3.14",
            "python3.13, my.custom.host/path/lambda/python:3.13",
            "python3.12, my.custom.host/path/lambda/python:3.12",
            "python3.11, my.custom.host/path/lambda/python:3.11",
            "python3.10, my.custom.host/path/lambda/python:3.10",
            "python3.9, my.custom.host/path/lambda/python:3.9",
            "nodejs24.x, my.custom.host/path/lambda/nodejs:24",
            "nodejs22.x, my.custom.host/path/lambda/nodejs:22",
            "nodejs20.x, my.custom.host/path/lambda/nodejs:20",
            "nodejs18.x, my.custom.host/path/lambda/nodejs:18",
            "nodejs16.x, my.custom.host/path/lambda/nodejs:16",
            "ruby3.4, my.custom.host/path/lambda/ruby:3.4",
            "ruby3.3, my.custom.host/path/lambda/ruby:3.3",
            "ruby3.2, my.custom.host/path/lambda/ruby:3.2",
            "dotnet10, my.custom.host/path/lambda/dotnet:10",
            "dotnet9, my.custom.host/path/lambda/dotnet:9",
            "dotnet8, my.custom.host/path/lambda/dotnet:8",
            "dotnet6, my.custom.host/path/lambda/dotnet:6",
            "go1.x, my.custom.host/path/lambda/go:1",
            "provided.al2023, my.custom.host/path/lambda/provided:al2023",
            "provided.al2, my.custom.host/path/lambda/provided:al2",
            "provided, my.custom.host/path/lambda/provided:latest"
    })
    void resolvesKnownRuntimesWithHostAndPathOverride(String runtime, String expectedImage) {
        EmulatorConfig customConfig = mock(EmulatorConfig.class);
        when(customConfig.ecrBaseUri()).thenReturn("my.custom.host/path");
        ImageResolver customResolver = new ImageResolver(customConfig);
        assertEquals(expectedImage, customResolver.resolve(runtime));
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
        AwsException ex = assertThrows(AwsException.class, () -> resolver.resolve("dotnet7"));
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
