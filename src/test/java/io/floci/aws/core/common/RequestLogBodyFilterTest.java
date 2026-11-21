package io.floci.aws.core.common;

import io.floci.aws.core.common.RequestLogFilter.Body;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RequestLogBodyFilter} reads a body only when its declared length is within the cap, so a slow or endless
 * upload never holds a worker thread, and always hands the resource the complete body.
 */
class RequestLogBodyFilterTest {

    @Test
    void aBodyWithNoDeclaredLengthIsNotRead() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.hasEntity()).thenReturn(true);
        // Undeclared, chunked length: reading it would wait on the client.
        when(ctx.getLength()).thenReturn(-1);

        Body body = RequestLogBodyFilter.captureRequestBody(ctx, () -> true);

        assertThat(body.text(), equalTo(RequestLogFilter.UNKNOWN_LENGTH));
        assertThat(body.originalSize(), equalTo(-1));
        verify(ctx, never()).getEntityStream();
    }

    @Test
    void aDeclaredLengthOverTheCapIsSkippedWithoutReading() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.hasEntity()).thenReturn(true);
        when(ctx.getLength()).thenReturn(RequestLogFilter.MAX_BODY_BYTES + 1);

        Body body = RequestLogBodyFilter.captureRequestBody(ctx, () -> true);

        assertThat(body.text(), equalTo(RequestLogFilter.TRUNCATED));
        assertThat(body.originalSize(), equalTo(RequestLogFilter.MAX_BODY_BYTES + 1));
        verify(ctx, never()).getEntityStream();
    }

    @Test
    void smallBodyIsCapturedAndReplayed() throws IOException {
        byte[] small = "Action=ListQueues&Version=2012-11-05".getBytes(StandardCharsets.UTF_8);
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.hasEntity()).thenReturn(true);
        when(ctx.getLength()).thenReturn(small.length);
        when(ctx.getEntityStream()).thenReturn(new ByteArrayInputStream(small));
        ArgumentCaptor<InputStream> replaced = ArgumentCaptor.forClass(InputStream.class);

        Body body = RequestLogBodyFilter.captureRequestBody(ctx, () -> true);

        assertThat(body.text(), equalTo("Action=ListQueues&Version=2012-11-05"));
        assertThat(body.encoding(), is(nullValue()));
        verify(ctx).setEntityStream(replaced.capture());
        assertThat(replaced.getValue().readAllBytes(), equalTo(small));
    }

    @Test
    void aBinaryBodyIsCapturedAsBase64AndReplayedWhole() throws IOException {
        byte[] binary = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x10, (byte) 0xC3};
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.hasEntity()).thenReturn(true);
        when(ctx.getLength()).thenReturn(binary.length);
        when(ctx.getEntityStream()).thenReturn(new ByteArrayInputStream(binary));
        ArgumentCaptor<InputStream> replaced = ArgumentCaptor.forClass(InputStream.class);

        Body body = RequestLogBodyFilter.captureRequestBody(ctx, () -> true);

        assertThat(body.encoding(), equalTo("base64"));
        assertThat(Base64.getDecoder().decode(body.text()), equalTo(binary));
        assertThat(body.originalSize(), equalTo(binary.length));
        verify(ctx).setEntityStream(replaced.capture());
        assertThat(replaced.getValue().readAllBytes(), equalTo(binary));
    }

    @Test
    void whatFollowsAnUndercountingLengthStillReachesTheResource() throws IOException {
        byte[] sent = "declared-and-more".getBytes(StandardCharsets.UTF_8);
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.hasEntity()).thenReturn(true);
        when(ctx.getLength()).thenReturn(8);
        when(ctx.getEntityStream()).thenReturn(new ByteArrayInputStream(sent));
        ArgumentCaptor<InputStream> replaced = ArgumentCaptor.forClass(InputStream.class);

        Body body = RequestLogBodyFilter.captureRequestBody(ctx, () -> true);

        assertThat(body.text(), equalTo("declared"));
        verify(ctx).setEntityStream(replaced.capture());
        assertThat(replaced.getValue().readAllBytes(), equalTo(sent));
    }

    @Test
    void noEntityReturnsNull() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.hasEntity()).thenReturn(false);

        assertThat(RequestLogBodyFilter.captureRequestBody(ctx, () -> true), is(nullValue()));
    }

    @Test
    void onTheIoThreadTheBodyIsLeftAlone() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.hasEntity()).thenReturn(true);
        when(ctx.getLength()).thenReturn(10);

        Body body = RequestLogBodyFilter.captureRequestBody(ctx, () -> false);

        assertThat(body.text(), equalTo(RequestLogFilter.NOT_BUFFERED_IO_THREAD));
        verify(ctx, never()).getEntityStream();
        verify(ctx, never()).setEntityStream(any());
    }
}
