package io.floci.aws.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code CapacityReservationSpecification} member of CreateAutoScalingGroup,
 * UpdateAutoScalingGroup and the {@code AutoScalingGroup} shape, with its
 * {@code CapacityReservationTarget} flattened into the two lists that target carries.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CapacityReservationSpecification {

    private String capacityReservationPreference;
    private List<String> capacityReservationIds = new ArrayList<>();
    private List<String> capacityReservationResourceGroupArns = new ArrayList<>();

    public CapacityReservationSpecification() {}

    public String getCapacityReservationPreference() { return capacityReservationPreference; }
    public void setCapacityReservationPreference(String v) { this.capacityReservationPreference = v; }

    public List<String> getCapacityReservationIds() { return capacityReservationIds; }
    public void setCapacityReservationIds(List<String> v) {
        this.capacityReservationIds = v != null ? new ArrayList<>(v) : new ArrayList<>();
    }

    public List<String> getCapacityReservationResourceGroupArns() { return capacityReservationResourceGroupArns; }
    public void setCapacityReservationResourceGroupArns(List<String> v) {
        this.capacityReservationResourceGroupArns = v != null ? new ArrayList<>(v) : new ArrayList<>();
    }

    @JsonIgnore
    public boolean hasTarget() {
        return !capacityReservationIds.isEmpty() || !capacityReservationResourceGroupArns.isEmpty();
    }
}
