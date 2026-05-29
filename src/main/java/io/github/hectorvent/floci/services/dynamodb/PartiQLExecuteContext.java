package io.github.hectorvent.floci.services.dynamodb;

class PartiQLExecuteContext {

    private Integer limit;
    private String nextToken;

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

    Integer limit() {
        return limit;
    }

    String nextToken() {
        return nextToken;
    }
}
