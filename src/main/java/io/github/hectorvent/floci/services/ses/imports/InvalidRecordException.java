package io.github.hectorvent.floci.services.ses.imports;

/**
 * A source record that cannot be turned into an import. Caught by the reader that raised it and
 * reported as a failed record, so one bad row never fails the whole job.
 */
final class InvalidRecordException extends RuntimeException {

    InvalidRecordException(String message) {
        super(message);
    }
}
