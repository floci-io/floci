package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class HttpParameters {

    @JsonProperty("PathParameterValues")
    private List<String> pathParameterValues;

    @JsonProperty("HeaderParameters")
    private Map<String, String> headerParameters;

    @JsonProperty("QueryStringParameters")
    private Map<String, String> queryStringParameters;

    public HttpParameters() {
    }

    public List<String> getPathParameterValues() {
        return pathParameterValues;
    }

    public void setPathParameterValues(List<String> pathParameterValues) {
        this.pathParameterValues = pathParameterValues;
    }

    public Map<String, String> getHeaderParameters() {
        return headerParameters;
    }

    public void setHeaderParameters(Map<String, String> headerParameters) {
        this.headerParameters = headerParameters;
    }

    public Map<String, String> getQueryStringParameters() {
        return queryStringParameters;
    }

    public void setQueryStringParameters(Map<String, String> queryStringParameters) {
        this.queryStringParameters = queryStringParameters;
    }
}
