package io.github.hectorvent.floci.services.ses.imports;

import java.io.IOException;

/**
 * Yields an import job's source records lazily, one at a time, so a large object is never held in
 * memory. {@code null} ends the source; a problem with the file as a whole rather than with one
 * record is an {@code AwsException} that fails the job.
 */
public interface RecordReader {

    ImportRecord next() throws IOException;
}
