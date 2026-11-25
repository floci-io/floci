/*
 * Adapted from docker-java com.github.dockerjava.core.DefaultInvocationBuilder (version 3.7.1, Apache License 2.0,
 * https://github.com/docker-java/docker-java). Package-private there; the copy runs each stream callback on a virtual thread (see executeAndStream).
 */
package io.floci.aws.core.common.docker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.BadRequestException;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.exception.NotAcceptableException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.exception.UnauthorizedException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.InvocationBuilder;
import com.github.dockerjava.core.MediaType;
import com.github.dockerjava.transport.DockerHttpClient;
import org.apache.commons.io.IOUtils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class FlociInvocationBuilder implements InvocationBuilder {

    /** docker-java stream callbacks running now, each on its own virtual thread. */
    private static final AtomicInteger ACTIVE_STREAMS = new AtomicInteger();

    /**
     * How much of a {@link #get()} body left unread at close is read to keep its pooled connection.
     * Past this the call is aborted instead: httpclient5 reads a body to its end on close, and an
     * archive closed after its first entry, as the Lambda working directory check does, would
     * otherwise download the whole directory.
     */
    static final long DRAIN_ON_CLOSE_LIMIT = 64 * 1024;

    private final DockerHttpClient.Request.Builder requestBuilder;
    private final DockerHttpClient dockerHttpClient;
    private final ObjectMapper objectMapper;

    FlociInvocationBuilder(DockerHttpClient dockerHttpClient, ObjectMapper objectMapper, String path) {
        this.requestBuilder = DockerHttpClient.Request.builder().path(path);
        this.dockerHttpClient = dockerHttpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public FlociInvocationBuilder accept(MediaType mediaType) {
        return header("accept", mediaType.getMediaType());
    }

    @Override
    public FlociInvocationBuilder header(String name, String value) {
        requestBuilder.putHeader(name, value);
        return this;
    }

    @Override
    public void delete() {
        DockerHttpClient.Request request = requestBuilder
            .method(DockerHttpClient.Request.Method.DELETE)
            .build();

        execute(request).close();
    }

    @Override
    public void get(ResultCallback<Frame> resultCallback) {
        DockerHttpClient.Request request = requestBuilder
            .method(DockerHttpClient.Request.Method.GET)
            .build();

        executeAndStream(
            request,
            resultCallback,
            new FlociFramedInputStreamConsumer(resultCallback)
        );
    }

    @Override
    public <T> T get(TypeReference<T> typeReference) {
        try (InputStream inputStream = get()) {
            return objectMapper.readValue(inputStream, typeReference);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> void get(TypeReference<T> typeReference, ResultCallback<T> resultCallback) {
        DockerHttpClient.Request request = requestBuilder
            .method(DockerHttpClient.Request.Method.GET)
            .build();

        executeAndStream(
            request,
            resultCallback,
            new JsonSink<>(typeReference, resultCallback)
        );
    }

    @Override
    public InputStream post(Object entity) {
        DockerHttpClient.Request request = requestBuilder
            .method(DockerHttpClient.Request.Method.POST)
            .putHeader("content-type", "application/json")
            .bodyBytes(encode(entity))
            .build();

        DockerHttpClient.Response response = execute(request);
        return new FilterInputStream(response.getBody()) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    response.close();
                }
            }
        };
    }

    @Override
    public <T> T post(Object entity, TypeReference<T> typeReference) {
        try {
            DockerHttpClient.Request request = requestBuilder
                .method(DockerHttpClient.Request.Method.POST)
                .putHeader("content-type", "application/json")
                .bodyBytes(encode(entity))
                .build();

            try (DockerHttpClient.Response response = execute(request)) {
                return objectMapper.readValue(response.getBody(), typeReference);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> void post(Object entity, TypeReference<T> typeReference, ResultCallback<T> resultCallback) {
        DockerHttpClient.Request request = requestBuilder
            .method(DockerHttpClient.Request.Method.POST)
            .putHeader("content-type", "application/json")
            .bodyBytes(encode(entity))
            .build();

        executeAndStream(
            request,
            resultCallback,
            new JsonSink<>(typeReference, resultCallback)
        );
    }

    @Override
    public <T> T post(TypeReference<T> typeReference, InputStream body) {
        try (InputStream inputStream = post(body)) {
            return objectMapper.readValue(inputStream, typeReference);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void post(Object entity, InputStream stdin, ResultCallback<Frame> resultCallback) {
        DockerHttpClient.Request request = requestBuilder
            .method(DockerHttpClient.Request.Method.POST)
            .putHeader("content-type", "application/json")
            .bodyBytes(encode(entity))
            .hijackedInput(stdin)
            .build();

        executeAndStream(
            request,
            resultCallback,
            new FlociFramedInputStreamConsumer(resultCallback)
        );
    }

    @Override
    public <T> void post(TypeReference<T> typeReference, ResultCallback<T> resultCallback, InputStream body) {
        DockerHttpClient.Request request = requestBuilder
            .method(DockerHttpClient.Request.Method.POST)
            .body(body)
            .build();

        executeAndStream(
            request,
            resultCallback,
            new JsonSink<>(typeReference, resultCallback)
        );
    }

    @Override
    public void postStream(InputStream body) {
        DockerHttpClient.Request request = requestBuilder
            .method(DockerHttpClient.Request.Method.POST)
            .body(body)
            .build();

        execute(request).close();
    }

    @Override
    public InputStream get() {
        DockerHttpClient.Request request = requestBuilder
            .method(DockerHttpClient.Request.Method.GET)
            .build();

        DockerHttpClient.Response response = execute(request);
        return new FilterInputStream(response.getBody()) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    if (endsWithin(in, DRAIN_ON_CLOSE_LIMIT)) {
                        super.close();
                    } else {
                        // Abort first: closing the body would read the rest of it to keep the connection.
                        response.close();
                        closeAbandoned();
                    }
                } finally {
                    response.close();
                }
            }

            private void closeAbandoned() {
                try {
                    super.close();
                } catch (IOException expected) {
                    // The aborted connection is gone, and the body was not wanted.
                }
            }
        };
    }

    /**
     * Whether {@code stream} ends within {@code limit} more bytes. Reads (and discards) them, so a
     * body that ends here gives its connection back to the pool; a read that fails counts as not ending.
     */
    static boolean endsWithin(InputStream stream, long limit) {
        byte[] buffer = new byte[8192];
        long read = 0;
        try {
            while (read <= limit) {
                int n = stream.read(buffer);
                if (n == -1) {
                    return true;
                }
                read += n;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    @Override
    public void put(InputStream body, MediaType mediaType) {
        DockerHttpClient.Request request = requestBuilder
            .method(DockerHttpClient.Request.Method.PUT)
            .putHeader("content-type", mediaType.toString())
            .body(body)
            .build();

        execute(request).close();
    }

    private DockerHttpClient.Response execute(DockerHttpClient.Request request) {
        try {
            DockerHttpClient.Response response = dockerHttpClient.execute(request);
            int statusCode = response.getStatusCode();
            if (statusCode < 200 || statusCode > 299) {
                try {
                    String body = IOUtils.toString(response.getBody(), StandardCharsets.UTF_8);
                    switch (statusCode) {
                        case 304:
                            throw new NotModifiedException(body);
                        case 400:
                            throw new BadRequestException(body);
                        case 401:
                            throw new UnauthorizedException(body);
                        case 404:
                            throw new NotFoundException(body);
                        case 406:
                            throw new NotAcceptableException(body);
                        case 409:
                            throw new ConflictException(body);
                        case 500:
                            throw new InternalServerErrorException(body);
                        default:
                            throw new DockerException(body, statusCode);
                    }
                } finally {
                    response.close();
                }
            } else {
                return response;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** How many stream callbacks are running, for the pool gauge line; virtual threads are not enumerable. */
    static int activeStreams() {
        return ACTIVE_STREAMS.get();
    }

    // The one change from docker-java: the stream runs on a virtual thread instead of a platform thread of
    // its own, so a container's log follow, wait or exec stream no longer costs a platform thread while it
    // waits. Closing the stream interrupts the thread as before; a virtual thread blocked in a socket read is
    // released by the interrupt as well as by the close.
    private <T> void executeAndStream(
        DockerHttpClient.Request request,
        ResultCallback<T> callback,
        Consumer<DockerHttpClient.Response> sourceConsumer
    ) {
        Thread.ofVirtual().name(DockerPoolGaugeLogger.STREAM_THREAD_PREFIX + Objects.hashCode(request)).start(() -> {
            ACTIVE_STREAMS.incrementAndGet();
            Thread streamingThread = Thread.currentThread();
            try (DockerHttpClient.Response response = execute(request)) {
                callback.onStart(() -> {
                    streamingThread.interrupt();
                    response.close();
                });

                sourceConsumer.accept(response);
                callback.onComplete();
            } catch (Exception e) {
                callback.onError(e);
            } finally {
                ACTIVE_STREAMS.decrementAndGet();
            }
        });
    }

    private byte[] encode(Object entity) {
        if (entity == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsBytes(entity);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private class JsonSink<T> implements Consumer<DockerHttpClient.Response> {

        private final TypeReference<T> typeReference;

        private final ResultCallback<T> resultCallback;

        JsonSink(TypeReference<T> typeReference, ResultCallback<T> resultCallback) {
            this.typeReference = typeReference;
            this.resultCallback = resultCallback;
        }

        @Override
        public void accept(DockerHttpClient.Response response) {
            try {
                InputStream body = response.getBody();
                MappingIterator<Object> iterator = objectMapper.readerFor(typeReference).readValues(body);
                while (iterator.hasNextValue()) {
                    resultCallback.onNext((T) iterator.nextValue());
                }
            } catch (Exception e) {
                resultCallback.onError(e);
            }
        }
    }
}
