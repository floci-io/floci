package io.github.hectorvent.floci.services.appsync.graphql;

public class ReturnSignal extends RuntimeException {
    private final Object value;

    public ReturnSignal(Object value) {
        super("return");
        this.value = value;
    }

    public Object getValue() {
        return value;
    }
}
