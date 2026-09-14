package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractTagsControllerTest {

    private TagDispatcher dispatcher;
    private TagHandler handler;
    private AbstractTagsController controller;

    private static class TestTagsController extends AbstractTagsController {
        TestTagsController(TagDispatcher dispatcher) {
            super(dispatcher);
        }
    }

    @BeforeEach
    void setUp() {
        dispatcher = mock(TagDispatcher.class);
        handler = mock(TagHandler.class);
        controller = new TestTagsController(dispatcher);
    }

    @Test
    void listTagsDelegatesToDispatcher() {
        HttpHeaders headers = mock(HttpHeaders.class);
        String arn = "arn:aws:apigateway:us-east-1::/restapis/123";
        Response expected = Response.ok().build();
        when(dispatcher.listTagsForArn(headers, arn)).thenReturn(expected);

        Response actual = controller.listTags(headers, arn);

        assertEquals(expected, actual);
        verify(dispatcher).listTagsForArn(headers, arn);
    }

    @Test
    void tagResourcePostDispatchesWithHandlerSuccessStatus() {
        HttpHeaders headers = mock(HttpHeaders.class);
        String arn = "arn:aws:kafka:us-east-1:000000000000:cluster/test/id";
        String body = "{\"tags\":{\"env\":\"test\"}}";
        when(dispatcher.resolveHandler(arn)).thenReturn(handler);
        when(handler.tagResourceSuccessStatus()).thenReturn(204);
        Response expected = Response.noContent().build();
        when(dispatcher.tagResourcePost(eq(headers), eq(arn), eq(body), any(Response.class))).thenReturn(expected);

        Response actual = controller.tagResourcePost(headers, arn, body);

        assertEquals(expected, actual);
        verify(dispatcher).tagResourcePost(eq(headers), eq(arn), eq(body), any(Response.class));
    }

    @Test
    void tagResourcePutDispatchesWithHandlerSuccessStatus() {
        HttpHeaders headers = mock(HttpHeaders.class);
        String arn = "arn:aws:apigateway:us-east-1::/restapis/123";
        String body = "{\"tags\":{\"env\":\"test\"}}";
        when(dispatcher.resolveHandler(arn)).thenReturn(handler);
        when(handler.tagResourceSuccessStatus()).thenReturn(200);
        Response expected = Response.ok().build();
        when(dispatcher.tagResourcePut(eq(headers), eq(arn), eq(body), any(Response.class))).thenReturn(expected);

        Response actual = controller.tagResourcePut(headers, arn, body);

        assertEquals(expected, actual);
        verify(dispatcher).tagResourcePut(eq(headers), eq(arn), eq(body), any(Response.class));
    }

    @Test
    void untagResourceDispatchesWithHandlerSuccessStatus() {
        HttpHeaders headers = mock(HttpHeaders.class);
        UriInfo uriInfo = mock(UriInfo.class);
        String arn = "arn:aws:apigateway:us-east-1::/restapis/123";
        when(dispatcher.resolveHandler(arn)).thenReturn(handler);
        when(handler.untagResourceSuccessStatus()).thenReturn(204);
        Response expected = Response.noContent().build();
        when(dispatcher.untagResourceForArn(eq(headers), eq(uriInfo), eq(arn), any(Response.class), eq(handler)))
                .thenReturn(expected);

        Response actual = controller.untagResource(headers, uriInfo, arn);

        assertEquals(expected, actual);
        verify(dispatcher).untagResourceForArn(eq(headers), eq(uriInfo), eq(arn), any(Response.class), eq(handler));
    }
}
