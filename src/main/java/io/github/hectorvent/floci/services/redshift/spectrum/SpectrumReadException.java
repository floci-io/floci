package io.github.hectorvent.floci.services.redshift.spectrum;

public class SpectrumReadException extends RuntimeException {

    private final String sqlState;

    public SpectrumReadException(String sqlState, String message) {
        super(message);
        this.sqlState = sqlState;
    }

    public SpectrumReadException(String sqlState, String message, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }
}
