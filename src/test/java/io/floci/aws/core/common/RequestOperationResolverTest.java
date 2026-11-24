package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestOperationResolver.ResolvedOperation;
import io.floci.aws.services.iam.IamActionRegistry;
import io.floci.aws.services.iam.ResourceArnBuilder;
import io.floci.aws.services.s3.S3Controller;
import io.floci.aws.testing.ParallelLocks;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RequestOperationResolver} on its own, outside {@link IamEnforcementFilter}, with the real service catalog
 * and action rules: each protocol resolves to the service that serves the request and the operation it calls there.
 */
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class RequestOperationResolverTest {

    private static final String SIGNED_FOR = "AWS4-HMAC-SHA256 "
            + "Credential=AKIAEXAMPLE/20261117/us-east-1/%s/aws4_request, "
            + "SignedHeaders=host;x-amz-date, Signature=deadbeef";

    private final ResolvedServiceCatalog catalog =
            new ResolvedServiceCatalog(mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));
    private final RequestOperationResolver resolver = new RequestOperationResolver(new IamActionRegistry(),
            new AwsQueryServiceResolver(catalog), new ResourceArnBuilder(new ObjectMapper()), catalog);

    @Test
    void aQueryCallResolvesToTheOperationItsActionNames() {
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("Action", "DescribeInstances");
        query.add("Version", "2016-11-15");
        ContainerRequestContext ctx = request("GET", "/", query, Map.of(),
                new ProtocolClaim(WireProtocol.AWS_QUERY, catalog.byExternalKey("ec2").orElseThrow(), null, null));

        ResolvedOperation resolved = resolver.resolve(SIGNED_FOR.formatted("ec2"), "ec2", ctx, null);

        assertEquals(new ResolvedOperation("ec2", "ec2:DescribeInstances", "ec2:DescribeInstances"), resolved);
    }

    @Test
    void aJsonCallResolvesToTheOperationItsTargetNames() {
        ContainerRequestContext ctx = request("POST", "/", new MultivaluedHashMap<>(),
                Map.of("X-Amz-Target", "Kinesis_20131202.PutRecord"), jsonClaim("kinesis", "PutRecord"));

        ResolvedOperation resolved = resolver.resolve(SIGNED_FOR.formatted("kinesis"), "kinesis", ctx, null);

        assertEquals(new ResolvedOperation("kinesis", "kinesis:PutRecord", "kinesis:PutRecord"), resolved);
    }

    @Test
    void aCallIsResolvedAsTheServiceThatServesItRatherThanTheScopeItIsSignedFor() {
        ContainerRequestContext ctx = request("POST", "/", new MultivaluedHashMap<>(),
                Map.of("X-Amz-Target", "DynamoDB_20120810.PutItem"), jsonClaim("dynamodb", "PutItem"));

        ResolvedOperation resolved = resolver.resolve(SIGNED_FOR.formatted("sqs"), "sqs", ctx, null);

        assertEquals("dynamodb", resolved.credentialScope());
        assertEquals("dynamodb:PutItem", resolved.action());
    }

    @Test
    void aRestRequestIsResolvedByItsRouteAndNotByAnXAmzTargetItCarries() {
        ContainerRequestContext ctx = request("GET", "/bucket/key", new MultivaluedHashMap<>(),
                Map.of("X-Amz-Target", "Kinesis_20131202.DeleteStream"), ProtocolClaim.rest());
        ResourceInfo route = mock(ResourceInfo.class);
        doReturn(S3Controller.class).when(route).getResourceClass();

        ResolvedOperation resolved = resolver.resolve(SIGNED_FOR.formatted("s3"), "s3", ctx, route);

        assertEquals(new ResolvedOperation("s3", "s3:GetObject", "s3:GetObject"), resolved);
    }

    @Test
    void aRequestNoRuleMapsKeepsTheScopeItIsSignedFor() {
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("Version", "2016-11-15");
        ContainerRequestContext ctx = request("GET", "/", query, Map.of(),
                new ProtocolClaim(WireProtocol.AWS_QUERY, catalog.byExternalKey("ec2").orElseThrow(), null, null));

        ResolvedOperation resolved = resolver.resolve(SIGNED_FOR.formatted("ec2"), "ec2", ctx, null);

        assertEquals(new ResolvedOperation("ec2", null, null), resolved);
    }

    private ProtocolClaim jsonClaim(String service, String operation) {
        ServiceDescriptor descriptor = catalog.byExternalKey(service).orElseThrow();
        return new ProtocolClaim(WireProtocol.AWS_JSON_1_1, descriptor, operation, null);
    }

    private static ContainerRequestContext request(String method, String path, MultivaluedMap<String, String> query,
                                                   Map<String, String> headers, ProtocolClaim claim) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn(method);
        when(ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY)).thenReturn(claim);
        when(ctx.getHeaderString(anyString())).thenAnswer(invocation -> headers.get(invocation.<String>getArgument(0)));
        when(ctx.getMediaType()).thenReturn("POST".equals(method)
                ? MediaType.valueOf("application/x-amz-json-1.1") : null);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost:4566" + path));
        when(uri.getBaseUri()).thenReturn(URI.create("http://localhost:4566/"));
        when(uri.getQueryParameters()).thenReturn(query);
        when(uri.getPathParameters()).thenReturn(new MultivaluedHashMap<>());
        when(ctx.getUriInfo()).thenReturn(uri);
        return ctx;
    }
}
