package io.floci.aws.services.apigateway;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.ApiGatewayException;
import software.amazon.awssdk.services.apigateway.model.EndpointType;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayDomainResourceOwnerValidationIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void unknownResourceOwnerIsRejected() {
        assertInvalidOwner("unknown");
    }

    @Test
    void emptyResourceOwnerIsRejected() {
        assertInvalidOwner("");
    }

    @Test
    void omittedResourceOwnerKeepsTheExistingDomainListing() {
        Fixture fixture = fixture();
        assertTrue(fixture.client().getDomainNames().items().stream()
                .anyMatch(domain -> fixture.domain().equals(domain.domainName())));
    }

    @Test
    void selfRemainsAnAcceptedSelector() {
        Fixture fixture = fixture();
        assertTrue(fixture.client().getDomainNames(r -> r.resourceOwner("SELF")).sdkHttpResponse().isSuccessful());
    }

    @Test
    void otherAccountsRemainsAnAcceptedSelector() {
        Fixture fixture = fixture();
        assertTrue(fixture.client().getDomainNames(r -> r.resourceOwner("OTHER_ACCOUNTS"))
                .sdkHttpResponse().isSuccessful());
    }

    private void assertInvalidOwner(String owner) {
        Fixture fixture = fixture();
        ApiGatewayException error = assertThrows(ApiGatewayException.class,
                () -> fixture.client().getDomainNames(r -> r.resourceOwner(owner)));
        assertEquals(400, error.statusCode());
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
        assertEquals("1 validation error detected: Value '" + owner + "' at 'resourceOwner' failed to satisfy "
                + "constraint: Member must satisfy enum value set: [SELF, OTHER_ACCOUNTS]",
                error.awsErrorDetails().errorMessage());
    }

    private Fixture fixture() {
        String account = Long.toString(ThreadLocalRandom.current().nextLong(100000000000L, 999999999999L));
        ApiGatewayClient client = AwsTestClients.create(ApiGatewayClient.builder(), account, Region.US_EAST_1);
        cleanup.register(client::close);
        String domain = "owner-validation-" + UUID.randomUUID() + ".example.com";
        client.createDomainName(r -> r.domainName(domain)
                .endpointConfiguration(c -> c.types(EndpointType.REGIONAL)));
        cleanup.register(() -> client.deleteDomainName(r -> r.domainName(domain)));
        return new Fixture(client, domain);
    }

    private record Fixture(ApiGatewayClient client, String domain) {}
}
