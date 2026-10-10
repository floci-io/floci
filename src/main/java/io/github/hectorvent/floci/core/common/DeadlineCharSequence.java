package io.github.hectorvent.floci.core.common;

/**
 * A regex subject whose {@code charAt} throws once the deadline passes, so a catastrophically
 * backtracking caller-supplied pattern aborts instead of holding a request thread.
 */
public final class DeadlineCharSequence implements CharSequence {
    private final CharSequence delegate;
    private final long deadlineNanos;
    private final String timeoutMessage;

    public DeadlineCharSequence(CharSequence delegate, long deadlineNanos, String timeoutMessage) {
        this.delegate = delegate;
        this.deadlineNanos = deadlineNanos;
        this.timeoutMessage = timeoutMessage;
    }

    @Override
    public char charAt(int index) {
        if (System.nanoTime() - deadlineNanos > 0) {
            throw new IllegalStateException(timeoutMessage);
        }
        return delegate.charAt(index);
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new DeadlineCharSequence(delegate.subSequence(start, end), deadlineNanos, timeoutMessage);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
