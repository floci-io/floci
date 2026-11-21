package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeVpcEndpointServicesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ServiceDetail;
import software.amazon.awssdk.services.ec2.model.ServiceTypeDetail;

import java.net.URI;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DynamoDB is, with S3, one of the two services AWS offers as a Gateway endpoint, and it is also
 * offered as an Interface endpoint. Terraform's aws_vpc_endpoint_service data source looks the
 * service up with a {@code service-name} filter, so it has to be in the default catalog.
 */
@QuarkusTest
class Ec2DynamoDbEndpointServiceIntegrationTest {

    private static final String DYNAMODB = "com.amazonaws.us-east-1.dynamodb";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void aServiceNameFilterFindsTheDynamoDbGatewayAndInterfaceService() {
        try (Ec2Client ec2 = client()) {
            DescribeVpcEndpointServicesResponse response = ec2.describeVpcEndpointServices(r -> r.filters(
                    Filter.builder().name("service-name").values(DYNAMODB).build()));

            assertEquals(List.of(DYNAMODB), response.serviceNames());
            assertEquals(List.of("Gateway", "Interface"), serviceTypes(response.serviceDetails().get(0)));
        }
    }

    @Test
    void aGatewayServiceTypeFilterIncludesDynamoDb() {
        try (Ec2Client ec2 = client()) {
            DescribeVpcEndpointServicesResponse response = ec2.describeVpcEndpointServices(r -> r.filters(
                    Filter.builder().name("service-type").values("Gateway").build()));

            assertThat(response.serviceNames(), hasItem(DYNAMODB));
        }
    }

    @Test
    void aRequestedDynamoDbServiceReportsBothEndpointTypes() {
        try (Ec2Client ec2 = client()) {
            DescribeVpcEndpointServicesResponse response =
                    ec2.describeVpcEndpointServices(r -> r.serviceNames(DYNAMODB));

            assertEquals(List.of("Gateway", "Interface"), serviceTypes(response.serviceDetails().get(0)));
        }
    }

    private static List<String> serviceTypes(ServiceDetail detail) {
        return detail.serviceType().stream().map(ServiceTypeDetail::serviceTypeAsString).toList();
    }

    private Ec2Client client() {
        return AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint);
    }
}
