package io.floci.aws.testing;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mocked requests sent as the Query controller dispatches them. IAM reads a request's form parameters only for a
 * Query request, a form posted to the root path, so a mocked form request that names no method or path is read as
 * carrying none, as a form sent to another protocol's route is.
 */
public final class MockQueryRequests {

    private MockQueryRequests() {
    }

    /**
     * {@code request}, a mock carrying a form body, posted to the root path with no query or path parameters. A test
     * that stubs its own {@link UriInfo} afterwards replaces this one.
     */
    public static ContainerRequestContext postedToRoot(ContainerRequestContext request) {
        when(request.getMethod()).thenReturn("POST");
        UriInfo root = mock(UriInfo.class);
        when(root.getPath()).thenReturn("/");
        when(root.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(root.getPathParameters()).thenReturn(new MultivaluedHashMap<>());
        when(request.getUriInfo()).thenReturn(root);
        return request;
    }
}
