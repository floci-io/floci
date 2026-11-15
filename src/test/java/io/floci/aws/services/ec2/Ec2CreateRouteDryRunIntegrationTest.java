package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateRouteRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Route;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class Ec2CreateRouteDryRunIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void dryRunLeavesTheRouteTableUnchangedAndNormalCreateStillWorks() {
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint);
                RouteFixture fixture = new RouteFixture(client)) {
            List<Route> before = fixture.routes();
            CreateRouteRequest request = fixture.request().gatewayId(fixture.gatewayId).dryRun(true).build();
            assertAwsError("DryRunOperation", 412, () -> client.createRoute(request));
            assertEquals(before, fixture.routes());
            assertTrue(client.createRoute(request.toBuilder().dryRun(false).build()).returnValue());
            assertEquals(before.size() + 1, fixture.routes().size());
        }
    }

    @Test
    void dryRunDoesNotResolveAMissingNatGatewayOrWriteARoute() {
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint);
                RouteFixture fixture = new RouteFixture(client)) {
            List<Route> before = fixture.routes();
            assertAwsError("DryRunOperation", 412, () -> client.createRoute(fixture.request()
                    .natGatewayId("nat-0123456789abcdef0").dryRun(true).build()));
            assertEquals(before, fixture.routes());
        }
    }

    @Test
    void deniedSignedUserCannotUseDryRunToBypassAuthorization() throws Exception {
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint);
                RouteFixture fixture = new RouteFixture(admin);
                SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "route-dryrun-")) {
            List<Route> before = fixture.routes();
            try (Ec2Client caller = AwsTestClients.create(Ec2Client.builder(), user.accessKeyId(),
                    user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
                assertAwsError("UnauthorizedOperation", 403, () -> caller.createRoute(fixture.request()
                        .gatewayId(fixture.gatewayId).dryRun(true).build()));
            }
            assertEquals(before, fixture.routes());
        }
    }

    @Test
    void invalidSignatureIsRejectedBeforeDryRun() throws Exception {
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint);
                RouteFixture fixture = new RouteFixture(admin);
                SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "route-signature-")) {
            List<Route> before = fixture.routes();
            try (Ec2Client caller = AwsTestClients.create(Ec2Client.builder(), user.accessKeyId(),
                    "incorrect-secret", Region.US_EAST_1, endpoint)) {
                Ec2Exception error = assertThrows(Ec2Exception.class, () -> caller.createRoute(fixture.request()
                        .gatewayId(fixture.gatewayId).dryRun(true).build()));
                assertEquals(403, error.statusCode());
                assertTrue(error.awsErrorDetails().rawResponse().asUtf8String()
                        .contains("<Code>SignatureDoesNotMatch</Code>"));
            }
            assertEquals(before, fixture.routes());
        }
    }

    @Test
    void signedUserDryRunRequiresTheCurrentCreateRoutePermission() throws Exception {
        String policy = """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Action":"ec2:CreateRoute","Resource":"*"
                }]}
                """;
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint);
                RouteFixture fixture = new RouteFixture(admin);
                SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "route-allowed-", "route", policy);
                Ec2Client caller = AwsTestClients.create(Ec2Client.builder(), user.accessKeyId(),
                    user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            List<Route> before = fixture.routes();
            CreateRouteRequest request = fixture.request().gatewayId(fixture.gatewayId).dryRun(true).build();
            assertAwsError("DryRunOperation", 412, () -> caller.createRoute(request));
            SignedIamUsers.iam(endpoint, "DeleteUserPolicy", Map.of("UserName", user.name(), "PolicyName", "route"))
                    .then().statusCode(200);
            assertAwsError("UnauthorizedOperation", 403, () -> caller.createRoute(request));
            assertEquals(before, fixture.routes());
        }
    }

    private static final class RouteFixture implements AutoCloseable {
        private final Ec2Client client;
        private final String vpcId;
        private String routeTableId;
        private String gatewayId;

        private RouteFixture(Ec2Client client) {
            this.client = client;
            vpcId = client.createVpc(request -> request.cidrBlock("10.197.0.0/16")).vpc().vpcId();
            try {
                routeTableId = client.createRouteTable(request -> request.vpcId(vpcId)).routeTable().routeTableId();
                gatewayId = client.createInternetGateway().internetGateway().internetGatewayId();
                client.attachInternetGateway(request -> request.internetGatewayId(gatewayId).vpcId(vpcId));
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private CreateRouteRequest.Builder request() {
            return CreateRouteRequest.builder().routeTableId(routeTableId).destinationCidrBlock("192.0.2.0/24");
        }

        private List<Route> routes() {
            return client.describeRouteTables(request -> request.routeTableIds(routeTableId)).routeTables().getFirst()
                    .routes();
        }

        @Override
        public void close() {
            try {
                if (routeTableId != null) {
                    client.deleteRouteTable(request -> request.routeTableId(routeTableId));
                }
            } finally {
                try {
                    if (gatewayId != null) {
                        try {
                            client.detachInternetGateway(request -> request.internetGatewayId(gatewayId).vpcId(vpcId));
                        } finally {
                            client.deleteInternetGateway(request -> request.internetGatewayId(gatewayId));
                        }
                    }
                } finally {
                    client.deleteVpc(request -> request.vpcId(vpcId));
                }
            }
        }
    }
}
