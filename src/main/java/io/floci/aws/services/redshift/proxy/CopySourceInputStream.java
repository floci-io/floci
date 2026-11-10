package io.floci.aws.services.redshift.proxy;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** S3 decoding errors are COPY errors; backend transport errors must remain IOExceptions. */
final class CopySourceInputStream extends FilterInputStream {
    private CopySourceInputStream(InputStream input) {
        super(input);
    }

    static InputStream open(InputStream input, boolean gzip) {
        try {
            return new CopySourceInputStream(gzip ? new GZIPInputStream(input) : input);
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    @Override
    public int read() {
        try {
            return in.read();
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        try {
            return in.read(buffer, offset, length);
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    private static S3CopySimulator.S3TransferException failure(IOException exception) {
        return new S3CopySimulator.S3TransferException("58030", "Unable to read or decompress S3 COPY data", exception);
    }
}
