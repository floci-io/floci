package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * An explicit {@code floci.hostname}, with an extra DNS suffix and S3 request authentication, for
 * the classes that check the host a service advertises or answers on. Quarkus builds the
 * application once per distinct profile class, and keeps every one it built for the rest of the
 * test JVM, so they share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.hostname=}{@value #HOSTNAME} is the host Floci advertises and answers on.
 *   {@code IotEndpointHostnameIntegrationTest} checks that DescribeEndpoint reports it,
 *   {@code ApiGatewayExecuteApiHostIntegrationTest} that a regional execute-api host under it
 *   reaches the API, and {@code S3ConfiguredEndpointIntegrationTest} that it lists the buckets
 *   while a subdomain of it still addresses a bucket. The name sits under
 *   {@code localhost.floci.io} without being one of Floci's built-in suffixes, so a host under it
 *   routes only through the configured hostname.</li>
 *   <li>{@code floci.dns.extra-suffixes=alias.}{@value #HOSTNAME} serves a second endpoint name the
 *   same way. {@code S3ConfiguredEndpointIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.s3.enforce-auth=true} checks the signature of every S3 request,
 *   against the host it was signed for. {@code S3ConfiguredEndpointIntegrationTest} relies on it,
 *   and signs every request it sends.</li>
 * </ul>
 *
 * <p>Each is inert where no class relies on it. Only {@code S3ConfiguredEndpointIntegrationTest}
 * calls S3 or sends a host under the extra suffix.
 * {@code ApiGatewayRestExecuteApiHostIntegrationTest} addresses its APIs through the
 * {@code amazonaws.com} and built-in {@code localhost.floci.io} host forms, which route the same
 * whatever the configured hostname.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here.
 */
public class ConfiguredHostnameProfile implements QuarkusTestProfile {

    /** The hostname this profile configures. */
    public static final String HOSTNAME = "configured-endpoint.localhost.floci.io";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.hostname", HOSTNAME,
                "floci.dns.extra-suffixes", "alias." + HOSTNAME,
                "floci.services.s3.enforce-auth", "true");
    }
}
