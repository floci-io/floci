package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One side of a handshake. {@code type} is {@code ACCOUNT}, {@code ORGANIZATION} or
 * {@code EMAIL}, and {@code id} is interpreted accordingly.
 */
@RegisterForReflection
public class HandshakeParty {

    private String id;
    private String type;

    public HandshakeParty() {
    }

    public HandshakeParty(String id, String type) {
        this.id = id;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
