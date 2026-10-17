package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * The IoT Core settings that differ from the test defaults, for the classes that test them.
 * Quarkus builds the application once per distinct profile class, so they share this one rather
 * than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.iot.endpoint-address=}{@value #ENDPOINT_ADDRESS} is what
 *   DescribeEndpoint returns for every endpoint type, and the domain name of the AWS-managed domain
 *   configurations. {@code IotDescribeEndpointOverrideIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.iot.rule-sql-strict=true} rejects a topic rule whose SQL is outside
 *   the subset Floci evaluates. {@code IotTopicRuleSqlStrictIntegrationTest} relies on it.</li>
 * </ul>
 *
 * <p>Each override is inert for the other class. The address only changes what DescribeEndpoint
 * and the AWS-managed domain configurations report, which the rule test never reads, and strict
 * SQL only changes how a topic rule is created, which the endpoint test never does.
 */
public class IotSettingsProfile implements QuarkusTestProfile {

    public static final String ENDPOINT_ADDRESS = "iot.example.localhost.floci.io";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.iot.endpoint-address", ENDPOINT_ADDRESS,
                "floci.services.iot.rule-sql-strict", "true");
    }
}
