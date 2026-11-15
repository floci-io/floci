package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.Placement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The {@code placement/} metadata an instance reads about its own zone. */
class Ec2MetadataPlacementTest {

    @Test
    void availabilityZoneIdIsTheIdOfTheInstancesZone() throws Exception {
        try (Ec2MetadataTestFixture imds = new Ec2MetadataTestFixture()) {
            imds.instance().setRegion("us-west-2");
            imds.instance().setPlacement(new Placement("us-west-2b"));
            imds.start();
            String token = imds.putToken("60").body();

            assertEquals("us-west-2b", imds.get("/latest/meta-data/placement/availability-zone", token).body());
            assertEquals("usw2-az2", imds.get("/latest/meta-data/placement/availability-zone-id", token).body());
            assertEquals("usw2-az2", imds.get("/latest/meta-data/placement/availability-zone-id/", token).body());
        }
    }
}
