/*
 * Adapted from docker-java 3.7.1, docker-java-transport-httpclient5,
 * com.github.dockerjava.httpclient5.HijackingHttpRequestExecutor.
 * Copyright docker-java contributors, licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). Changes: moved into Floci's package, JBoss
 * Logging conventions; the upgrade logic is unchanged.
 */
package io.github.hectorvent.floci.core.common.docker;

import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.io.HttpResponseInformationCallback;
import org.apache.hc.core5.http.io.entity.AbstractHttpEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.io.Closer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Executes a hijacked request (exec and attach with stdin): sends the upgrade, expects
 * {@code 101 Switching Protocols}, then streams the caller's input on a daemon thread while the
 * response body is read as the output stream. Every other request goes through the stock executor.
 */
class HijackingHttpRequestExecutor extends HttpRequestExecutor {

    static final String HIJACKED_INPUT_ATTRIBUTE = "com.github.docker-java.hijackedInput";

    HijackingHttpRequestExecutor(ConnectionReuseStrategy connectionReuseStrategy) {
        super(connectionReuseStrategy);
    }

    @Override
    public ClassicHttpResponse execute(ClassicHttpRequest request, HttpClientConnection conn,
                                       HttpResponseInformationCallback informationCallback,
                                       HttpContext context) throws IOException, HttpException {
        Objects.requireNonNull(request, "HTTP request");
        Objects.requireNonNull(conn, "Client connection");
        Objects.requireNonNull(context, "HTTP context");

        InputStream hijackedInput = (InputStream) context.getAttribute(HIJACKED_INPUT_ATTRIBUTE);
        if (hijackedInput != null) {
            return executeHijacked(request, conn, (HttpCoreContext) context, hijackedInput);
        }
        return super.execute(request, conn, informationCallback, context);
    }

    private ClassicHttpResponse executeHijacked(ClassicHttpRequest request, HttpClientConnection conn,
                                                HttpCoreContext context, InputStream hijackedInput)
            throws HttpException, IOException {
        try {
            context.setSSLSession(conn.getSSLSession());
            context.setEndpointDetails(conn.getEndpointDetails());
            ProtocolVersion transportVersion = request.getVersion();
            if (transportVersion != null) {
                context.setProtocolVersion(transportVersion);
            }

            conn.sendRequestHeader(request);
            conn.sendRequestEntity(request);
            conn.flush();

            ClassicHttpResponse response = conn.receiveResponseHeader();
            if (response.getCode() != HttpStatus.SC_SWITCHING_PROTOCOLS) {
                conn.terminateRequest(request);
                throw new ProtocolException("Expected 101 Switching Protocols, got: " + new StatusLine(response));
            }

            Thread thread = new Thread(() -> {
                try {
                    BasicClassicHttpRequest fakeRequest = new BasicClassicHttpRequest("POST", "/");
                    fakeRequest.setHeader(HttpHeaders.CONTENT_LENGTH, Long.MAX_VALUE);
                    fakeRequest.setEntity(new HijackedEntity(hijackedInput));
                    conn.sendRequestEntity(fakeRequest);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            thread.setName("docker-java-httpclient5-hijacking-stream-" + System.identityHashCode(request));
            thread.setDaemon(true);
            thread.start();

            // The upgraded stream is read as an ordinary response body: 101 becomes 200.
            response.setCode(200);
            conn.receiveResponseEntity(response);
            return response;
        } catch (HttpException | IOException | RuntimeException e) {
            Closer.closeQuietly(conn);
            throw e;
        }
    }

    private static final class HijackedEntity extends AbstractHttpEntity {

        private final InputStream inStream;

        HijackedEntity(InputStream inStream) {
            super((String) null, null, false);
            this.inStream = inStream;
        }

        @Override
        public void writeTo(OutputStream outStream) throws IOException {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, read);
                outStream.flush();
            }
        }

        @Override
        public InputStream getContent() {
            return inStream;
        }

        @Override
        public boolean isStreaming() {
            return true;
        }

        @Override
        public boolean isRepeatable() {
            return false;
        }

        @Override
        public void close() throws IOException {
            inStream.close();
        }

        @Override
        public long getContentLength() {
            return -1;
        }
    }
}
