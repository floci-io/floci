package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceTypeInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class Ec2ProcessorManufacturerIntegrationTest {

    @ParameterizedTest
    @CsvFileSource(resources = "/ec2/processor-manufacturers.csv", numLinesToSkip = 1)
    void processorManufacturerMatchesAwsSnapshot(String name, String manufacturer) {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            List<InstanceTypeInfo> types = ec2.describeInstanceTypes(r -> r.instanceTypesWithStrings(name)).instanceTypes();
            assertEquals(1, types.size());
            assertEquals(name, types.getFirst().instanceTypeAsString());
            assertEquals(manufacturer, types.getFirst().processorInfo().manufacturer());
        }
    }
}
