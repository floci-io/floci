package io.github.hectorvent.floci.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public class EndpointConfiguration {

    private List<EndpointType> types = new ArrayList<>();
    private List<String> vpcEndpointIds = new ArrayList<>();

    public List<EndpointType> getTypes() {
        return types;
    }

    public void setTypes(List<EndpointType> types) {
        this.types = types != null ? types : new ArrayList<>();
    }

    public List<String> getVpcEndpointIds() {
        return vpcEndpointIds;
    }

    public void setVpcEndpointIds(List<String> vpcEndpointIds) {
        this.vpcEndpointIds = vpcEndpointIds != null ? vpcEndpointIds : new ArrayList<>();
    }
}
