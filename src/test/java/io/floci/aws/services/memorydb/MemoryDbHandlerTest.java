package io.floci.aws.services.memorydb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.memorydb.model.Acl;
import io.floci.aws.services.memorydb.model.AuthMode;
import io.floci.aws.services.memorydb.model.Cluster;
import io.floci.aws.services.memorydb.model.ClusterStatus;
import io.floci.aws.services.memorydb.model.Endpoint;
import io.floci.aws.services.memorydb.model.User;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        when(service.clustersUsingAcl(any())).thenReturn(List.of());
        when(service.aclNamesForUser(any())).thenReturn(List.of());
    }

    @Test
    void createClusterPropagatesAclNameToSpec() throws Exception {
        JsonNode request = objectMapper.readTree(
                "{\"ClusterName\":\"secure\",\"ACLName\":\"app-acl\"}");

        handler.handle("CreateCluster", request, "us-east-1");

        ArgumentCaptor<Cluster> captor = ArgumentCaptor.forClass(Cluster.class);
        verify(service).createCluster(captor.capture(), eq("us-east-1"));
        assertEquals("app-acl", captor.getValue().getAclName());
    }

    @Test
    void createUserParsesAuthenticationMode() throws Exception {
        when(service.createUser(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        JsonNode request = objectMapper.readTree(
                "{\"UserName\":\"app-user\","
                        + "\"AccessString\":\"on ~* +@all\","
                        + "\"AuthenticationMode\":{\"Type\":\"password\",\"Passwords\":[\"s3cret\"]}}");

        Response response = handler.handle("CreateUser", request, "us-east-1");
        assertEquals(200, response.getStatus());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(service).createUser(captor.capture(), eq("us-east-1"));
        User spec = captor.getValue();
        assertEquals("app-user", spec.getName());
        assertEquals(AuthMode.PASSWORD, spec.getAuthMode());
        assertEquals(List.of("s3cret"), spec.getPasswords());
    }

    @Test
    void createAclParsesUserNames() throws Exception {
        when(service.createAcl(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        JsonNode request = objectMapper.readTree(
                "{\"ACLName\":\"app-acl\",\"UserNames\":[\"app-user\"]}");

        Response response = handler.handle("CreateACL", request, "us-east-1");
        assertEquals(200, response.getStatus());

        ArgumentCaptor<Acl> captor = ArgumentCaptor.forClass(Acl.class);
        verify(service).createAcl(captor.capture(), eq("us-east-1"));
        assertEquals(List.of("app-user"), captor.getValue().getUserNames());
    }

    @Test
    void createUserAndAclReadTags() throws Exception {
        when(service.createUser(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(service.createAcl(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        String tags = "\"Tags\":[{\"Key\":\"team\",\"Value\":\"red\"}]";

        handler.handle("CreateUser", objectMapper.readTree(
                "{\"UserName\":\"tag-user\",\"AccessString\":\"on ~* +@all\","
                        + "\"AuthenticationMode\":{\"Type\":\"iam\"}," + tags + "}"), "us-east-1");
        handler.handle("CreateACL", objectMapper.readTree(
                "{\"ACLName\":\"tag-acl\",\"UserNames\":[\"default\"]," + tags + "}"), "us-east-1");

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(service).createUser(user.capture(), eq("us-east-1"));
        assertEquals(Map.of("team", "red"), user.getValue().getTags());
        ArgumentCaptor<Acl> acl = ArgumentCaptor.forClass(Acl.class);
        verify(service).createAcl(acl.capture(), eq("us-east-1"));
        assertEquals(Map.of("team", "red"), acl.getValue().getTags());
    }

    @Test
    void unknownOperationReturns400() throws Exception {
        JsonNode request = objectMapper.readTree("{}");
        Response response = handler.handle("Bogus", request, "us-east-1");
        assertEquals(400, response.getStatus());
    }
}
