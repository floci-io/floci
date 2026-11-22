/*
 * Adapted from docker-java com.github.dockerjava.core.FramedInputStreamConsumer (version 3.7.1, Apache License 2.0,
 * https://github.com/docker-java/docker-java). Package-private there, and FlociInvocationBuilder needs it to read multiplexed stream frames.
 */
package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.transport.DockerHttpClient;

import java.io.InputStream;
import java.util.Arrays;
import java.util.function.Consumer;

final class FlociFramedInputStreamConsumer implements Consumer<DockerHttpClient.Response> {

    private final ResultCallback<Frame> resultCallback;

    FlociFramedInputStreamConsumer(ResultCallback<Frame> resultCallback) {
        this.resultCallback = resultCallback;
    }

    @Override
    public void accept(DockerHttpClient.Response response) {
        try {
            InputStream body = response.getBody();

            byte[] buffer = new byte[1024];
            while (true) {
                // See https://docs.docker.com/engine/api/v1.37/#operation/ContainerAttach
                // [8]byte{STREAM_TYPE, 0, 0, 0, SIZE1, SIZE2, SIZE3, SIZE4}[]byte{OUTPUT}

                int streamTypeByte = body.read();
                if (streamTypeByte < 0) {
                    return;
                }

                StreamType streamType = streamType(streamTypeByte);

                if (streamType == StreamType.RAW) {
                    resultCallback.onNext(new Frame(StreamType.RAW, new byte[]{(byte) streamTypeByte}));

                    int readBytes;
                    while ((readBytes = body.read(buffer)) >= 0) {
                        if (readBytes == buffer.length) {
                            resultCallback.onNext(new Frame(StreamType.RAW, buffer));
                        } else {
                            resultCallback.onNext(new Frame(StreamType.RAW, Arrays.copyOf(buffer, readBytes)));
                        }
                    }
                    return;
                }

                // Skip 3 bytes
                for (int i = 0; i < 3; i++) {
                    if (body.read() < 0) {
                        return;
                    }
                }

                // uint32 encoded as big endian.
                int bytesToRead = 0;
                for (int i = 0; i < 4; i++) {
                    int readByte = body.read();
                    if (readByte < 0) {
                        return;
                    }
                    bytesToRead |= (readByte & 0xff) << (8 * (3 - i));
                }

                do {
                    int readBytes = body.read(buffer, 0, Math.min(buffer.length, bytesToRead));
                    if (readBytes < 0) {
                        // TODO log?
                        return;
                    }

                    if (readBytes == buffer.length) {
                        resultCallback.onNext(new Frame(streamType, buffer));
                    } else {
                        resultCallback.onNext(new Frame(streamType, Arrays.copyOf(buffer, readBytes)));
                    }
                    bytesToRead -= readBytes;
                } while (bytesToRead > 0);
            }
        } catch (Exception e) {
            resultCallback.onError(e);
        }
    }

    private static StreamType streamType(int streamType) {
        switch (streamType) {
            case 0:
                return StreamType.STDIN;
            case 1:
                return StreamType.STDOUT;
            case 2:
                return StreamType.STDERR;
            default:
                return StreamType.RAW;
        }
    }
}
