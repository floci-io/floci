package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

/**
 * Maps AWS Lambda runtime identifiers to ECR Public image URIs.
 * Custom image URIs (containing '/' or ':') are passed through unchanged.
 */
@ApplicationScoped
public class ImageResolver {

    private static final Map<String, String> RUNTIME_TO_IMAGE = Map.ofEntries(
            Map.entry("java25", "java:25"),
            Map.entry("java21", "java:21"),
            Map.entry("java17", "java:17"),
            Map.entry("java11", "java:11"),
            Map.entry("java8.al2", "java:8.al2"),
            Map.entry("java8", "java:8"),
            Map.entry("python3.14", "python:3.14"),
            Map.entry("python3.13", "python:3.13"),
            Map.entry("python3.12", "python:3.12"),
            Map.entry("python3.11", "python:3.11"),
            Map.entry("python3.10", "python:3.10"),
            Map.entry("python3.9", "python:3.9"),
            Map.entry("nodejs24.x", "nodejs:24"),
            Map.entry("nodejs22.x", "nodejs:22"),
            Map.entry("nodejs20.x", "nodejs:20"),
            Map.entry("nodejs18.x", "nodejs:18"),
            Map.entry("nodejs16.x", "nodejs:16"),
            Map.entry("ruby3.4", "ruby:3.4"),
            Map.entry("ruby3.3", "ruby:3.3"),
            Map.entry("ruby3.2", "ruby:3.2"),
            Map.entry("dotnet10", "dotnet:10"),
            Map.entry("dotnet9", "dotnet:9"),
            Map.entry("dotnet8", "dotnet:8"),
            Map.entry("dotnet6", "dotnet:6"),
            Map.entry("go1.x", "go:1"),
            Map.entry("provided.al2023", "provided:al2023"),
            Map.entry("provided.al2", "provided:al2"),
            Map.entry("provided", "provided:latest")
    );

    private final String baseUri;

    public ImageResolver(EmulatorConfig config) {
        this.baseUri = config.ecrBaseUri();
    }

    public String resolve(String runtime) {
        if (runtime == null || runtime.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "Runtime is required", 400);
        }
        // Custom image URI passthrough
        if (runtime.contains("/") || runtime.contains(":")) {
            return runtime;
        }
        String image = RUNTIME_TO_IMAGE.get(runtime);
        if (image == null) {
            throw new AwsException("InvalidParameterValueException",
                    "The runtime parameter " + runtime + " is not supported.", 400);
        }
        return baseUri + "/lambda/" + image;
    }
}
