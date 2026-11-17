package io.floci.aws.services.emrserverless.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A stored session: the session as GetSession reports it, plus the client token and the request it came with, which
 * decide whether a repeated StartSession is a retry or a conflict, and whether a Spark Connect server runs for it.
 */
@RegisterForReflection
public class SessionRecord {
    private Session session;
    private String clientToken;
    private JsonNode request;
    // Whether Floci runs a Spark Connect server for it, as it does in the docker runner mode.
    private boolean containerSession;

    public Session getSession() { return session; }
    public void setSession(Session session) { this.session = session; }
    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public JsonNode getRequest() { return request; }
    public void setRequest(JsonNode request) { this.request = request; }
    public boolean isContainerSession() { return containerSession; }
    public void setContainerSession(boolean containerSession) { this.containerSession = containerSession; }
}
