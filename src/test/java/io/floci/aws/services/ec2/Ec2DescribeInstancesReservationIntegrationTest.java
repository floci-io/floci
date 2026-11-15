package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every instance keeps the reservation of the RunInstances call that launched it. */
@QuarkusTest
class Ec2DescribeInstancesReservationIntegrationTest {

    @Test
    void instancesLaunchedTogetherShareTheReservationRunInstancesAnswered() {
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "test", "test", Region.US_EAST_1)) {
            RunInstancesResponse run = client.runInstances(request -> request.imageId("ami-0abcdef1234567890")
                    .instanceType("t3.micro").minCount(3).maxCount(3));
            List<String> ids = run.instances().stream().map(Instance::instanceId).toList();
            try {
                Reservation first = reservationOf(client, ids);
                Reservation second = reservationOf(client, ids);

                assertEquals(run.reservationId(), first.reservationId());
                assertEquals(first.reservationId(), second.reservationId());
                assertEquals(3, first.instances().size());
                assertTrue(first.instances().stream().map(Instance::instanceId).toList().containsAll(ids));
            } finally {
                client.terminateInstances(request -> request.instanceIds(ids));
            }
        }
    }

    private static Reservation reservationOf(Ec2Client client, List<String> ids) {
        List<Reservation> reservations = client.describeInstances(request -> request.instanceIds(ids)).reservations();
        assertEquals(1, reservations.size());
        return reservations.getFirst();
    }
}
