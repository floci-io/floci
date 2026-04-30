package io.github.hectorvent.floci.services.athena;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AthenaServiceTest {

    private static final String BASE_URL = "http://localhost:4566";

    @Test
    void resolveS3EndpointForDuck_usesDockerHostAddressWhenEmbeddedDnsInactive() {
        // Floci runs in a container, embedded DNS is not active.
        // The Floci container's IP (resolved by DockerHostResolver) is reachable
        // from the floci-duck container on the same Docker network.
        String endpoint = AthenaService.resolveS3EndpointForDuck(
                BASE_URL,
                Optional.empty(),
                Optional.empty(),
                "172.30.0.2");

        assertEquals("http://172.30.0.2:4566", endpoint);
    }

    @Test
    void resolveS3EndpointForDuck_usesHostDockerInternalWhenFlociOnHost() {
        // Floci runs natively on the host. DockerHostResolver returns
        // host.docker.internal so containers can reach back.
        String endpoint = AthenaService.resolveS3EndpointForDuck(
                BASE_URL,
                Optional.empty(),
                Optional.empty(),
                "host.docker.internal");

        assertEquals("http://host.docker.internal:4566", endpoint);
    }

    @Test
    void resolveS3EndpointForDuck_usesDefaultSuffixWhenEmbeddedDnsActiveAndHostnameUnset() {
        // Embedded DNS is active and no hostname is configured.
        // Containers using the embedded DNS resolve the default suffix to Floci.
        String endpoint = AthenaService.resolveS3EndpointForDuck(
                BASE_URL,
                Optional.of("172.30.0.2"),
                Optional.empty(),
                "ignored-fallback");

        assertEquals("http://localhost.floci.io:4566", endpoint);
    }

    @Test
    void resolveS3EndpointForDuck_usesConfiguredHostnameWhenEmbeddedDnsActive() {
        // Embedded DNS is active and a custom hostname is configured.
        String endpoint = AthenaService.resolveS3EndpointForDuck(
                BASE_URL,
                Optional.of("172.30.0.2"),
                Optional.of("floci.local"),
                "ignored-fallback");

        assertEquals("http://floci.local:4566", endpoint);
    }

    @Test
    void resolveS3EndpointForDuck_neverReturnsLocalhost() {
        // Regression: previously the s3_endpoint was config.baseUrl() ("http://localhost:4566"),
        // which inside the duck container points at duck itself, breaking S3 reads.
        // Whichever branch fires, the hostname must not be "localhost".
        String endpointOnHost = AthenaService.resolveS3EndpointForDuck(
                BASE_URL, Optional.empty(), Optional.empty(), "host.docker.internal");
        String endpointInContainer = AthenaService.resolveS3EndpointForDuck(
                BASE_URL, Optional.empty(), Optional.empty(), "172.30.0.2");
        String endpointWithDns = AthenaService.resolveS3EndpointForDuck(
                BASE_URL, Optional.of("172.30.0.2"), Optional.empty(), "ignored");

        assertEquals(false, endpointOnHost.contains("localhost"),
                "expected non-localhost endpoint, got " + endpointOnHost);
        assertEquals(false, endpointInContainer.contains("localhost"),
                "expected non-localhost endpoint, got " + endpointInContainer);
        // Note: the embedded-DNS branch's default suffix happens to start with "localhost." —
        // that is a *resolvable* DNS name pointing to Floci's IP, not the loopback alias.
        assertEquals("http://localhost.floci.io:4566", endpointWithDns);
    }
}
