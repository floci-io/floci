package io.github.hectorvent.floci.services.dynamodb;

class PartiQLExecuteContext {

    private Integer limit;
    private String nextToken;
    private String tokenBinding;
    private boolean consistentRead;

    private PartiQLExecuteContext() {
    }

    static PartiQLExecuteContext builder() {
        return new PartiQLExecuteContext();
    }

    PartiQLExecuteContext limit(Integer limit) {
        this.limit = limit;
        return this;
    }

    PartiQLExecuteContext nextToken(String nextToken) {
        this.nextToken = nextToken;
        return this;
    }

    PartiQLExecuteContext tokenBinding(String tokenBinding) {
        this.tokenBinding = tokenBinding;
        return this;
    }

    PartiQLExecuteContext consistentRead(boolean consistentRead) {
        this.consistentRead = consistentRead;
        return this;
    }

    boolean consistentRead() {
        return consistentRead;
    }

    Integer limit() {
        return limit;
    }

    String nextToken() {
        return nextToken;
    }

    String tokenBinding() {
        return tokenBinding;
    }
}
