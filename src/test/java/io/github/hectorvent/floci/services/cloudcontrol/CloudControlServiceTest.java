package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudControlServiceTest {

    private final List<CloudControlService> services = new ArrayList<>();

    @AfterEach
    void shutDownServices() {
        services.forEach(CloudControlService::shutdown);
    }

    /** Registers a service so {@link #shutDownServices()} stops its worker and drainer threads. */
    private CloudControlService track(CloudControlService service) {
        services.add(service);
        return service;
    }

    @Test
    void accountScopesCreateStatusLookupAndDelete() throws Exception {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        StackResource resource = new StackResource();
        resource.setPhysicalId("vpc-account-a");
        resource.setAttributes(Map.of("VpcId", "vpc-account-a"));
        when(provisioner.provisionStandalone(eq("AWS::EC2::VPC"),
                any(), eq("us-east-1"),
                eq("111111111111"))).thenReturn(resource);
        CloudControlService service = track(new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class), provisioner,
                new ObjectMapper()));

        CloudControlService.ProgressEvent pending = service.createResource(
                "us-east-1", "111111111111", "AWS::EC2::VPC", "{\"CidrBlock\":\"10.0.0.0/16\"}");
        awaitSuccess(service, "111111111111", pending.requestToken());
        assertEquals("vpc-account-a", service.getResource("us-east-1", "111111111111",
                "AWS::EC2::VPC", "vpc-account-a").identifier());
        assertThrows(AwsException.class, () -> service.requestStatus("222222222222", pending.requestToken()));
        assertThrows(AwsException.class, () -> service.getResource("us-east-1", "222222222222",
                "AWS::EC2::VPC", "vpc-account-a"));

        CloudControlService.ProgressEvent deniedDelete = service.deleteResource(
                "us-east-1", "222222222222", "AWS::EC2::VPC", "vpc-account-a");
        assertEquals("FAILED", deniedDelete.operationStatus());
        verify(provisioner, never()).deleteStandalone(anyString(), anyString(), anyString(), anyMap());

        CloudControlService.ProgressEvent deleted = service.deleteResource(
                "us-east-1", "111111111111", "AWS::EC2::VPC", "vpc-account-a");
        assertEquals("SUCCESS", deleted.operationStatus());
        verify(provisioner).deleteStandalone("AWS::EC2::VPC", "vpc-account-a", "us-east-1",
                Map.of("VpcId", "vpc-account-a"));
    }

    @Test
    void restoresAccountOwnersAndRequestStateFromMetadataStores() throws Exception {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        StackResource resource = new StackResource();
        resource.setPhysicalId("igw-persisted");
        when(provisioner.provisionStandalone(eq("AWS::EC2::InternetGateway"),
                any(), eq("us-east-1"),
                eq("111111111111"))).thenReturn(resource);
        AccountAwareStorageBackend<CloudControlService.PersistedRequest> requests =
                AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<CloudControlService.PersistedCreatedResource> created =
                AccountAwareStorageBackend.inMemory("000000000000");
        CloudControlService first = track(new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class), provisioner,
                new ObjectMapper(), requests, created));
        CloudControlService.ProgressEvent pending = first.createResource("us-east-1", "111111111111",
                "AWS::EC2::InternetGateway", "{}");
        awaitSuccess(first, "111111111111", pending.requestToken());
        CloudControlService restarted = track(new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class), provisioner,
                new ObjectMapper(), requests, created));

        assertEquals("SUCCESS", restarted.requestStatus("111111111111", pending.requestToken()).operationStatus());
        assertEquals("igw-persisted", restarted.getResource("us-east-1", "111111111111",
                "AWS::EC2::InternetGateway", "igw-persisted").identifier());
        assertThrows(AwsException.class, () -> restarted.requestStatus("222222222222", pending.requestToken()));
        assertThrows(AwsException.class, () -> restarted.getResource("us-east-1", "222222222222",
                "AWS::EC2::InternetGateway", "igw-persisted"));
    }

    @Test
    void replayAdmitsPersistedCreatesWhenTheQueueIsFull() throws Exception {
        String accountId = "111111111111";
        String region = "us-east-1";
        String typeName = "AWS::EC2::VPC";
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        CfnResourceDispatcher provisioner = blockingProvisioner(started, release, completed);
        AccountAwareStorageBackend<CloudControlService.PersistedRequest> requestStore =
                AccountAwareStorageBackend.inMemory("000000000000");
        List<String> tokens = List.of("replay-1", "replay-2", "replay-3");
        seedInProgressCreates(requestStore, accountId, region, typeName, tokens);

        // The single worker stays blocked on `release` for the whole of recovery, so the first replay
        // runs, the second fills the queue, and the third overflows.
        CloudControlService service = track(new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class), provisioner,
                new ObjectMapper(), requestStore, AccountAwareStorageBackend.inMemory("000000000000"), 1, 1));

        assertTrue(started.await(10, TimeUnit.SECONDS), "the worker never started");
        assertEquals(0, completed.get(),
                "recovery waited for a provisioning task to free a slot before returning");
        assertEquals(1, service.replayOverflowCount(),
                "the third replayed create should have overflowed the full worker queue");

        release.countDown();
        for (String token : tokens) {
            awaitSuccess(service, accountId, token);
        }
        verify(provisioner, times(3)).provisionStandalone(eq(typeName), any(), eq(region), eq(accountId));
        assertEquals(3, requestStore.scanAllAccountEntries(k -> true).size());
    }

    @Test
    void shutdownStopsTheReplayDrainerAndKeepsEveryCreateForTheNextStart() throws Exception {
        String accountId = "111111111111";
        CountDownLatch started = new CountDownLatch(1);
        CfnResourceDispatcher provisioner = blockingProvisioner(started, new CountDownLatch(1), new AtomicInteger());
        AccountAwareStorageBackend<CloudControlService.PersistedRequest> requestStore =
                AccountAwareStorageBackend.inMemory("000000000000");
        List<String> tokens = List.of("replay-1", "replay-2", "replay-3", "replay-4");
        seedInProgressCreates(requestStore, accountId, "us-east-1", "AWS::EC2::VPC", tokens);

        // One replay runs, one fills the queue, and two are parked. Shutdown empties the queue, so a
        // drainer that kept going would refill it with replay-3 and then block on replay-4 forever.
        CloudControlService service = track(new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class), provisioner,
                new ObjectMapper(), requestStore, AccountAwareStorageBackend.inMemory("000000000000"), 1, 1));
        assertTrue(started.await(10, TimeUnit.SECONDS), "the worker never started");
        Thread drainer = service.replayDrainer();
        assertNotNull(drainer, "the overflowing replays should have started the drainer");

        service.shutdown();
        drainer.join(10_000);
        assertTrue(service.awaitTermination(10, TimeUnit.SECONDS), "the worker outlived shutdown");

        assertFalse(drainer.isAlive(), "the replay drainer outlived shutdown");
        // replay-1 was mid-provisioning and saw the shutdown's interrupt, replay-2 was queued, and
        // replay-3 and replay-4 were parked. None of them failed, so all four replay on the next start.
        for (String token : tokens) {
            assertEquals("IN_PROGRESS", requestStore.getForAccount(accountId, token).orElseThrow()
                    .event().operationStatus(), token + " should stay persisted for the next start");
        }
    }

    @Test
    void emitsOnlyAwsShapedTagsForMalformedPersistedData() throws Exception {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Vpc vpc = new Vpc();
        vpc.setVpcId("vpc-test");
        vpc.setTags(List.of(
                new Tag(null, "ignored-null"),
                new Tag("", "ignored-empty"),
                new Tag("  ", "ignored-blank"),
                new Tag("Name", null)));
        when(ec2Service.describeVpcs("us-east-1", List.of(), Map.of())).thenReturn(List.of(vpc));
        ObjectMapper mapper = new ObjectMapper();
        CloudControlService service = track(new CloudControlService(
                mock(S3Service.class), ec2Service, mock(IamService.class),
                mock(CfnResourceDispatcher.class), mapper));

        String properties = service.listResources("us-east-1", "AWS::EC2::VPC").getFirst().properties();
        JsonNode tags = mapper.readTree(properties).path("Tags");

        assertTrue(tags.isArray());
        assertEquals(1, tags.size());
        assertTrue(tags.get(0).path("Key").isTextual());
        assertEquals("Name", tags.get(0).path("Key").asText());
        assertTrue(tags.get(0).path("Value").isTextual());
        assertEquals("", tags.get(0).path("Value").asText());
        assertFalse(properties.contains("ignored-null"));
        assertFalse(properties.contains("ignored-empty"));
        assertFalse(properties.contains("ignored-blank"));
    }

    @Test
    void listResourcesRejectsARealButUnbackedTypeInsteadOfReturningEmpty() {
        CloudControlService service = track(new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class),
                mock(CfnResourceDispatcher.class), new ObjectMapper()));

        AwsException e = assertThrows(AwsException.class,
                () -> service.listResources("us-east-1", "AWS::SQS::Queue"));

        assertEquals("UnsupportedActionException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void listResourcesRejectsATypeThatDoesNotExistInAwsAtAll() {
        CloudControlService service = track(new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class),
                mock(CfnResourceDispatcher.class), new ObjectMapper()));

        AwsException e = assertThrows(AwsException.class,
                () -> service.listResources("us-east-1", "AWS::NoSuch::Type"));

        assertEquals("UnsupportedActionException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void listResourcesStillReturnsASupportedType() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.listBuckets()).thenReturn(List.of());
        CloudControlService service = track(new CloudControlService(
                s3Service, mock(Ec2Service.class), mock(IamService.class),
                mock(CfnResourceDispatcher.class), new ObjectMapper()));

        assertTrue(service.listResources("us-east-1", "AWS::S3::Bucket").isEmpty());
    }

    @Test
    void rejectsCreateWhenTheWorkerQueueIsFull() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CfnResourceDispatcher provisioner = blockingProvisioner(started, release, new AtomicInteger());

        AccountAwareStorageBackend<CloudControlService.PersistedRequest> requests =
                AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<CloudControlService.PersistedCreatedResource> created =
                AccountAwareStorageBackend.inMemory("000000000000");
        CloudControlService service = track(new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class), provisioner,
                new ObjectMapper(), requests, created, 1, 1));

        // The single worker takes the first task, the second queues, and the third overflows.
        service.createResource("us-east-1", "111111111111", "AWS::EC2::VPC", "{}");
        assertTrue(started.await(10, TimeUnit.SECONDS), "the worker never started");
        CloudControlService.ProgressEvent queued = service.createResource(
                "us-east-1", "111111111111", "AWS::EC2::VPC", "{}");

        AwsException e = assertThrows(AwsException.class,
                () -> service.createResource("us-east-1", "111111111111", "AWS::EC2::VPC", "{}"));
        assertEquals("ThrottlingException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());

        // The rejected token was dropped, so only the two accepted requests remain persisted.
        assertEquals(2, requests.scanAllAccountEntries(k -> true).size());

        // Once the worker drains, submission recovers.
        release.countDown();
        awaitSuccess(service, "111111111111", queued.requestToken());
        assertNotNull(service.createResource("us-east-1", "111111111111", "AWS::EC2::VPC", "{}"));
    }

    /**
     * A provisioner whose every call counts down {@code started}, then blocks on {@code release}
     * before returning a VPC numbered by {@code completed}. A release that never comes fails the
     * create, so a test cannot pass on a provisioner that was never let go.
     */
    private static CfnResourceDispatcher blockingProvisioner(CountDownLatch started, CountDownLatch release,
                                                             AtomicInteger completed) {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        doAnswer(invocation -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the provisioner");
            }
            StackResource resource = new StackResource();
            resource.setPhysicalId("vpc-" + completed.incrementAndGet());
            return resource;
        }).when(provisioner).provisionStandalone(anyString(), any(), anyString(), anyString());
        return provisioner;
    }

    /** Persists IN_PROGRESS creates as a previous run would have left them, oldest first. */
    private static void seedInProgressCreates(
            AccountAwareStorageBackend<CloudControlService.PersistedRequest> requestStore,
            String accountId, String region, String typeName, List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            CloudControlService.ProgressEvent event = new CloudControlService.ProgressEvent(
                    typeName, null, token, "CREATE", "IN_PROGRESS", null, null, accountId);
            requestStore.putForAccount(accountId, token,
                    new CloudControlService.PersistedRequest(event, region, "{}", i + 1L));
        }
    }

    private static void awaitSuccess(CloudControlService service, String accountId, String requestToken)
            throws InterruptedException {
        CloudControlService.ProgressEvent status = service.requestStatus(accountId, requestToken);
        for (int i = 0; i < 50 && !"SUCCESS".equals(status.operationStatus()); i++) {
            Thread.sleep(10);
            status = service.requestStatus(accountId, requestToken);
        }
        assertEquals("SUCCESS", status.operationStatus(),
                requestToken + " ended " + status.operationStatus() + ": " + status.statusMessage());
    }
}
