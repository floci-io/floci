package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.model.Endpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryDbHandlerTest {

    private MemoryDbService service;
    private MemoryDbHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(MemoryDbService.class);
        handler = new MemoryDbHandler(service, objectMapper);
        when(service.createCluster(any(), any())).thenAnswer(inv -> {
            Cluster spec = inv.getArgument(0);
            spec.setStatus(ClusterStatus.AVAILABLE);
            spec.setClusterEndpoint(new Endpoint("localhost", 6400));
            spec.setCreatedAt(Instant.now());
            return spec;
        });
    }

    @Test
    void createClusterPropagatesAuthTokenToSpec() throws Exception {
        JsonNode request = objectMapper.readTree(
                "{\"ClusterName\":\"secure\",\"AuthToken\":\"s3cret\"}");

        handler.handle("CreateCluster", request, "us-east-1");

        ArgumentCaptor<Cluster> captor = ArgumentCaptor.forClass(Cluster.class);
        org.mockito.Mockito.verify(service).createCluster(captor.capture(), eq("us-east-1"));
        Cluster spec = captor.getValue();
        assertEquals("s3cret", spec.getAuthToken(),
                "AuthToken must be copied onto the spec so PASSWORD auth can validate it");
        assertEquals(AuthMode.PASSWORD, spec.getAuthMode());
    }

    @Test
    void createClusterWithoutAuthTokenDefaultsToNoAuth() throws Exception {
        JsonNode request = objectMapper.readTree("{\"ClusterName\":\"open\"}");

        handler.handle("CreateCluster", request, "us-east-1");

        ArgumentCaptor<Cluster> captor = ArgumentCaptor.forClass(Cluster.class);
        org.mockito.Mockito.verify(service).createCluster(captor.capture(), eq("us-east-1"));
        assertEquals(AuthMode.NO_AUTH, captor.getValue().getAuthMode());
    }
}
