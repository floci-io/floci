package io.floci.aws.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ListSessionsResponse {
    private List<SessionSummary> sessions;
    private String nextToken;

    public List<SessionSummary> getSessions() { return sessions; }
    public void setSessions(List<SessionSummary> sessions) { this.sessions = sessions; }
    public String getNextToken() { return nextToken; }
    public void setNextToken(String nextToken) { this.nextToken = nextToken; }
}
