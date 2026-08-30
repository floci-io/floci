package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Endpoint {
    private String address;
    private int port;

    public Endpoint() {}

    public Endpoint(String address, int port) {
        this.address = address;
        this.port = port;
    }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}
