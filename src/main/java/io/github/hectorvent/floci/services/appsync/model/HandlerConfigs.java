package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HandlerConfigs {
    private HandlerConfig onPublish;
    private HandlerConfig onSubscribe;

    public HandlerConfig getOnPublish() { return onPublish; }
    public void setOnPublish(HandlerConfig onPublish) { this.onPublish = onPublish; }

    public HandlerConfig getOnSubscribe() { return onSubscribe; }
    public void setOnSubscribe(HandlerConfig onSubscribe) { this.onSubscribe = onSubscribe; }
}
