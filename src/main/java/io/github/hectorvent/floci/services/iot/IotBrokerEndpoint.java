package io.github.hectorvent.floci.services.iot;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Shared holder for the running MQTT broker's reachable address.
 *
 * <p>Set by {@link IotMqttBroker} on startup and read by {@code DescribeEndpoint} so clients
 * are pointed at the real local broker rather than a synthetic AWS hostname.
 */
@ApplicationScoped
public class IotBrokerEndpoint {

    private volatile boolean running;
    private volatile String host;
    private volatile int wsPort;
    private volatile int tcpPort;
    private volatile int wssPort = -1;
    private volatile int mqttsPort = -1;
    private volatile String wsPath = "/mqtt";

    public boolean isRunning() {
        return running;
    }

    public void set(String host, int wsPort, int tcpPort, String wsPath) {
        this.host = host;
        this.wsPort = wsPort;
        this.tcpPort = tcpPort;
        this.wsPath = wsPath;
        this.running = true;
    }

    public void setTlsPorts(int wssPort, int mqttsPort) {
        this.wssPort = wssPort;
        this.mqttsPort = mqttsPort;
    }

    public void clear() {
        this.running = false;
        this.wssPort = -1;
        this.mqttsPort = -1;
    }

    /**
     * Host clients should connect to. {@code 0.0.0.0} is advertised as {@code localhost}.
     */
    public String advertisedHost() {
        return (host == null || host.equals("0.0.0.0")) ? "localhost" : host;
    }

    public int wsPort() {
        return wsPort;
    }

    public int tcpPort() {
        return tcpPort;
    }

    public int wssPort() {
        return wssPort;
    }

    public int mqttsPort() {
        return mqttsPort;
    }

    public boolean tlsEnabled() {
        return wssPort > 0;
    }

    public String wsPath() {
        return wsPath;
    }
}
