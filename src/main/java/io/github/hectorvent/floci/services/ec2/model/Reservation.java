package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Reservation {

    private String reservationId;
    private String ownerId;
    private List<GroupIdentifier> groups = new ArrayList<>();
    private List<Instance> instances = new ArrayList<>();

    public Reservation() {}

    public String getReservationId() { return reservationId; }
    public void setReservationId(String reservationId) { this.reservationId = reservationId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public List<GroupIdentifier> getGroups() { return groups; }
    public void setGroups(List<GroupIdentifier> groups) { this.groups = groups; }

    public List<Instance> getInstances() { return instances; }
    public void setInstances(List<Instance> instances) { this.instances = instances; }
}
