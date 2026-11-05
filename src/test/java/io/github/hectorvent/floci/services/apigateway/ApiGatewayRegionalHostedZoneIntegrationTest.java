package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.EndpointType;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.DomainNameConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hosted zone both custom domain APIs give an SDK client for a region whose General Reference
 * row is not the default region's: {@code regionalHostedZoneId} on REST, {@code hostedZoneId} on HTTP.
 */
@QuarkusTest
class ApiGatewayRegionalHostedZoneIntegrationTest {

    private static final Region REGION = Region.EU_WEST_1;
    private static final String ZONE = "ZLY8HYME6SFDD";

    @Test
    void restDomainNameReportsThePublishedZone() {
        String domain = "v1.regional-zone-it.example.com";
        try (ApiGatewayClient client = AwsTestClients.create(ApiGatewayClient.builder(), "000000000000", REGION)) {
            try {
                String created = client.createDomainName(request -> request.domainName(domain)
                                .endpointConfiguration(endpoint -> endpoint.types(EndpointType.REGIONAL)))
                        .regionalHostedZoneId();
                String read = client.getDomainName(request -> request.domainName(domain)).regionalHostedZoneId();

                assertEquals(ZONE, created);
                assertEquals(ZONE, read);
            } finally {
                client.deleteDomainName(request -> request.domainName(domain));
            }
        }
    }

    @Test
    void httpDomainNameReportsThePublishedZone() {
        String domain = "v2.regional-zone-it.example.com";
        try (ApiGatewayV2Client client = AwsTestClients.create(ApiGatewayV2Client.builder(), "000000000000", REGION)) {
            try {
                DomainNameConfiguration created = client.createDomainName(request -> request.domainName(domain)
                                .domainNameConfigurations(configuration -> configuration.endpointType("REGIONAL")))
                        .domainNameConfigurations().get(0);
                DomainNameConfiguration read = client.getDomainName(request -> request.domainName(domain))
                        .domainNameConfigurations().get(0);

                assertEquals(ZONE, created.hostedZoneId());
                assertEquals(ZONE, read.hostedZoneId());
            } finally {
                client.deleteDomainName(request -> request.domainName(domain));
            }
        }
    }
}
