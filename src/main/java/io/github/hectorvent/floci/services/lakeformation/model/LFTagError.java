package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class LFTagError {
    private ErrorDetail error;
    private LFTagPair lfTag;

    public ErrorDetail getError() {
        return error;
    }

    public void setError(ErrorDetail error) {
        this.error = error;
    }

    public LFTagPair getLfTag() {
        return lfTag;
    }

    public void setLfTag(LFTagPair lfTag) {
        this.lfTag = lfTag;
    }
}
