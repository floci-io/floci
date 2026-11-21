package io.floci.aws.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class GetSessionResponse {
    private Session session;

    public Session getSession() { return session; }
    public void setSession(Session session) { this.session = session; }
}
