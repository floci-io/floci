package io.github.hectorvent.floci.services.redshift.spectrum;

public class SpectrumSqlException extends RuntimeException {

    private final String sqlState;

    public SpectrumSqlException(String sqlState, String message) {
        super(message);
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }
}
