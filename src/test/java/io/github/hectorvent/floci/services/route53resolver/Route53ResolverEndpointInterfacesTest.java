package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.testing.ThreadStates;
import io.github.hectorvent.floci.testutil.LogCapture;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How a resolver endpoint's EC2 interfaces are cleaned up when its create fails or races a delete. */
class Route53ResolverEndpointInterfacesTest {

    private static final String REGION = "us-east-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryStorage<String, ObjectNode> endpoints = new InMemoryStorage<>();
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final AtomicInteger eniCount = new AtomicInteger();

    private Route53ResolverService service(InMemoryStorage<String, ObjectNode> ipRequests) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-aaa");
        subnet.setVpcId("vpc-aaa");
        when(ec2.describeSubnets(eq(REGION), anyList(), any())).thenReturn(List.of(subnet));
        return new Route53ResolverService(new InMemoryStorage<>(), endpoints, new InMemoryStorage<>(),
                new InMemoryStorage<>(), ipRequests, new InMemoryStorage<>(), objectMapper, ec2);
    }

    private NetworkInterface eni() {
        int n = eniCount.incrementAndGet();
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-" + n);
        eni.setPrivateIpAddress("10.0.0." + (10 + n));
        return eni;
    }

    private ObjectNode request() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("Name", "ep");
        request.put("Direction", "INBOUND");
        request.putArray("SecurityGroupIds").add("sg-aaa");
        request.putArray("IpAddresses").add(objectMapper.createObjectNode().put("SubnetId", "subnet-aaa"))
                .add(objectMapper.createObjectNode().put("SubnetId", "subnet-aaa"));
        return request;
    }

    @Test
    void aFailureThatIsNotAnEc2RefusalStillDeletesTheInterfacesAlreadyMade() {
        Route53ResolverService service = service(new InMemoryStorage<>());
        IllegalStateException boom = new IllegalStateException("boom");
        when(ec2.createNetworkInterface(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> eni())
                .thenThrow(boom);

        assertSame(boom, assertThrows(IllegalStateException.class,
                () -> service.createResolverEndpoint(request(), REGION, "000000000000")));

        verify(ec2).releaseNetworkInterfaceFromService(REGION, "eni-1");
        verify(ec2).deleteNetworkInterface(REGION, "eni-1");
        assertTrue(endpoints.scan(k -> true).isEmpty());
    }

    @Test
    void aDeleteThatRacesTheCreateStillDeletesTheEndpointsInterfaces() throws Exception {
        CountDownLatch inPut = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorage<String, ObjectNode> ipRequests = new InMemoryStorage<>() {
            @Override
            public void put(String key, ObjectNode value) {
                inPut.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.put(key, value);
            }
        };
        Route53ResolverService service = service(ipRequests);
        when(ec2.createNetworkInterface(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> eni());

        Thread create = new Thread(() -> service.createResolverEndpoint(request(), REGION, "000000000000"));
        create.start();
        assertTrue(inPut.await(10, TimeUnit.SECONDS));
        // The endpoint is stored; its record, naming the interfaces, is not yet.
        String id = endpoints.scan(k -> true).getFirst().get("Id").asText();
        AtomicReference<Throwable> deleteError = new AtomicReference<>();
        Thread delete = new Thread(() -> {
            try {
                service.deleteResolverEndpoint(id);
            } catch (Throwable t) {
                deleteError.set(t);
            }
        });
        delete.start();
        ThreadStates.awaitBlockedOrDone(delete);
        release.countDown();
        create.join(10_000);
        delete.join(10_000);
        assertFalse(delete.isAlive(), "the delete must finish once the create is released");

        assertEquals(null, deleteError.get());
        verify(ec2).deleteNetworkInterface(REGION, "eni-1");
        verify(ec2).deleteNetworkInterface(REGION, "eni-2");
        assertTrue(ipRequests.scan(k -> true).isEmpty(), "no orphan record may outlive the endpoint");
        assertTrue(endpoints.scan(k -> true).isEmpty());
    }

    @Test
    void aCleanupFailureOtherThanAMissingInterfaceIsLoggedAndTheRestAreStillDeleted() {
        Route53ResolverService service = service(new InMemoryStorage<>());
        when(ec2.createNetworkInterface(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> eni());
        String id = service.createResolverEndpoint(request(), REGION, "000000000000").get("Id").asText();
        doThrow(new AwsException("InvalidParameterValue", "Network interface 'eni-1' is currently in use", 400))
                .when(ec2).deleteNetworkInterface(REGION, "eni-1");
        doThrow(new AwsException("InvalidNetworkInterfaceID.NotFound", "The network interface ID 'eni-2' does not exist",
                400)).when(ec2).deleteNetworkInterface(REGION, "eni-2");

        List<LogRecord> records = LogCapture.capture(Route53ResolverService.class,
                () -> service.deleteResolverEndpoint(id));

        List<LogRecord> warnings = records.stream().filter(r -> r.getLevel().intValue() == Level.WARNING.intValue()).toList();
        assertEquals(1, warnings.size(), "only the unexpected failure is logged");
        assertTrue(Arrays.asList(warnings.getFirst().getParameters()).contains("eni-1"));
        verify(ec2).deleteNetworkInterface(REGION, "eni-2");
    }
}
