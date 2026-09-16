package io.github.hectorvent.floci.services.apigateway;

import java.io.IOException;
import java.io.Writer;

/**
 * Wraps a delegate {@link Writer} and throws {@link VtlLimitExceededException} once the total
 * number of characters written would exceed a configured budget.
 *
 * <p>This defends against unbounded VTL template output regardless of cause (a huge
 * {@code #foreach} range, a single oversized reference, or repeated small writes that add up),
 * complementing the loop-iteration cap and the execution-time deadline, neither of which observe
 * output size directly.
 */
public class BoundedWriter extends Writer {

    private final Writer delegate;
    private final long maxChars;
    private long written;

    public BoundedWriter(Writer delegate, long maxChars) {
        this.delegate = delegate;
        this.maxChars = maxChars;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        if (written + len > maxChars) {
            throw new VtlLimitExceededException(
                    "VTL template output exceeded the configured size limit of " + maxChars + " characters");
        }
        delegate.write(cbuf, off, len);
        written += len;
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
