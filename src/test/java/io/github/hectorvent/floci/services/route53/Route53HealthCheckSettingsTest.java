package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Route53HealthCheckSettingsTest {

    private static String request(String config) {
        return """
                <CreateHealthCheckRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <CallerReference>ref</CallerReference>
                  <HealthCheckConfig>%s</HealthCheckConfig>
                </CreateHealthCheckRequest>
                """.formatted(config);
    }

    private static String settings(String config) {
        return Route53Controller.healthCheckSettings(request(config));
    }

    // Catches: a retry that states RequestInterval or FailureThreshold at its default refused as a
    // different request from one that omitted them.
    @Test
    void omittedIntervalAndThresholdEqualTheirDefaults() {
        assertEquals(settings("<Type>HTTP</Type><IPAddress>10.0.0.1</IPAddress>"),
                settings("<Type>HTTP</Type><IPAddress>10.0.0.1</IPAddress>"
                        + "<RequestInterval>30</RequestInterval><FailureThreshold>3</FailureThreshold>"));
        assertNotEquals(settings("<Type>HTTP</Type><IPAddress>10.0.0.1</IPAddress>"),
                settings("<Type>HTTP</Type><IPAddress>10.0.0.1</IPAddress><RequestInterval>10</RequestInterval>"));
    }

    // Catches: requests that differ only in a setting Floci does not model passing as a retry.
    @Test
    void settingsFlociDoesNotModelStillCount() {
        assertNotEquals(settings("<Type>HTTPS</Type><FullyQualifiedDomainName>example.com</FullyQualifiedDomainName>"),
                settings("<Type>HTTPS</Type><FullyQualifiedDomainName>example.com</FullyQualifiedDomainName>"
                        + "<EnableSNI>false</EnableSNI>"));
    }

    @Test
    void regionsAreASet() {
        assertEquals(settings("<Type>HTTP</Type><Regions><Region>us-east-1</Region><Region>eu-west-1</Region></Regions>"),
                settings("<Type>HTTP</Type><Regions><Region>eu-west-1</Region><Region>us-east-1</Region></Regions>"));
    }

    @Test
    void recoveryControlGetsNoIntervalOrThresholdDefaults() {
        assertEquals("RoutingControlArn=arn\nType=RECOVERY_CONTROL",
                settings("<Type>RECOVERY_CONTROL</Type><RoutingControlArn>arn</RoutingControlArn>"));
    }

    @Test
    void malformedXmlIsInvalidInput() {
        AwsException e = assertThrows(AwsException.class,
                () -> Route53Controller.healthCheckSettings("<CreateHealthCheckRequest><HealthCheckConfig>"));
        assertEquals("InvalidInput", e.getErrorCode());
    }
}
