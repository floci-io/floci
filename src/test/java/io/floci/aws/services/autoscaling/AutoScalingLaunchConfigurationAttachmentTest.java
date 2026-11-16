package io.floci.aws.services.autoscaling;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.autoscaling.model.AsgOptionalFields;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Subnet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingLaunchConfigurationAttachmentTest {
    private static final String REGION = "us-east-1";
    private AutoScalingService service;
    private Ec2Service ec2;

    @BeforeEach
    void setUp() {
        service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.createAutoScalingGroup(REGION, "existing", null, "lt-original", null, "1", null,
                0, 0, 0, 300, List.of(), List.of(), List.of(), List.of(),
                "EC2", 0, List.of(), Map.of(), Map.of(), AsgOptionalFields.none());
        service.createLaunchConfiguration(REGION, "configuration", null, "ami-test", "t3.micro",
                null, List.of(), null, null, null);
        ec2 = mock(Ec2Service.class);
        service.ec2Service = ec2;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionWaitsForAttachmentAlreadyPastValidation(boolean update) throws Exception {
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> deletingThread = new AtomicReference<>();
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-test");
        subnet.setAvailabilityZone(REGION + "a");
        when(ec2.describeSubnets(eq(REGION), eq(List.of("subnet-test")), isNull())).thenAnswer(invocation -> {
            validated.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "Attachment was not released");
            return List.of(subnet);
        });
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> attachment = workers.submit(() -> attachConfiguration(update));
            assertTrue(validated.await(5, TimeUnit.SECONDS));
            Future<AwsException> deletion = workers.submit(() -> {
                deletingThread.set(Thread.currentThread());
                return assertThrows(AwsException.class,
                        () -> service.deleteLaunchConfiguration(REGION, "configuration"));
            });
            await().atMost(Duration.ofSeconds(5)).until(() -> deletion.isDone()
                    || deletingThread.get() != null && deletingThread.get().getState() == Thread.State.BLOCKED);
            assertFalse(deletion.isDone(), "Deletion must wait for the attachment it raced");
            workers.submit(() -> {
                service.createLaunchConfiguration("us-west-2", "configuration", null, "ami-test", "t3.micro",
                        null, List.of(), null, null, null);
                service.deleteLaunchConfiguration("us-west-2", "configuration");
            }).get(5, TimeUnit.SECONDS);
            release.countDown();
            attachment.get(5, TimeUnit.SECONDS);
            assertEquals("ResourceInUse", deletion.get(5, TimeUnit.SECONDS).getErrorCode());
            assertEquals(1, service.describeLaunchConfigurations(REGION, List.of("configuration")).size());
            String groupName = update ? "existing" : "created";
            assertEquals("configuration", service.describeAutoScalingGroups(REGION, List.of(groupName))
                    .getFirst().getLaunchConfigurationName());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void attachmentRejectsAConfigurationDeletedBeforeValidation(boolean update) {
        service.deleteLaunchConfiguration(REGION, "configuration");

        AwsException failure = assertThrows(AwsException.class, () -> attachConfiguration(update));

        assertEquals("ValidationError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("configuration"));
        assertTrue(service.describeAutoScalingGroups(REGION, List.of("created")).isEmpty());
        assertNull(service.describeAutoScalingGroups(REGION, List.of("existing"))
                .getFirst().getLaunchConfigurationName());
        verifyNoInteractions(ec2);
    }

    private void attachConfiguration(boolean update) {
        if (update) {
            service.updateAutoScalingGroup(REGION, "existing", "configuration", null, null, null, null,
                    null, null, null, null, List.of(), List.of("subnet-test"),
                    null, null, null, AsgOptionalFields.none());
        } else {
            service.createAutoScalingGroup(REGION, "created", "configuration", null, null, null, null,
                    0, 0, 0, 300, List.of(), List.of("subnet-test"), List.of(), List.of(),
                    "EC2", 0, List.of(), Map.of(), Map.of(), AsgOptionalFields.none());
        }
    }
}
