package io.floci.aws.services.codeartifact;

import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.Path;

/**
 * The one request-body shape BaGetter's and pypiserver's own streamed {@code
 * storePackageVersionAsset} overloads share: a multipart body whose file part streams straight
 * from disk via {@code BodyPublishers.concat} rather than being copied into one combined array,
 * sandwiched between caller-built {@code head}/{@code tail} byte fragments specific to each
 * protocol's own field layout.
 */
final class MultipartFilePublisher {

    private MultipartFilePublisher() {
    }

    static HttpRequest.BodyPublisher concat(byte[] head, Path content, byte[] tail) {
        try {
            return BodyPublishers.concat(BodyPublishers.ofByteArray(head), BodyPublishers.ofFile(content),
                    BodyPublishers.ofByteArray(tail));
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
    }
}
