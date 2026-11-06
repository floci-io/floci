package io.floci.aws.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuleCondition {

    private String field;
    private List<String> values = new ArrayList<>();

    // typed configs
    private List<String> hostHeaderValues = new ArrayList<>();
    private List<String> pathPatternValues = new ArrayList<>();
    private String httpHeaderName;
    private List<String> httpHeaderValues = new ArrayList<>();
    private List<String> httpMethodValues = new ArrayList<>();
    private List<String> sourceIpValues = new ArrayList<>();
    private List<QueryStringPair> queryStringValues = new ArrayList<>();

    public RuleCondition() {}

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }

    public List<String> getValues() { return values; }
    public void setValues(List<String> values) { this.values = values; }

    public List<String> getHostHeaderValues() { return hostHeaderValues; }
    public void setHostHeaderValues(List<String> hostHeaderValues) { this.hostHeaderValues = hostHeaderValues; }

    public List<String> getPathPatternValues() { return pathPatternValues; }
    public void setPathPatternValues(List<String> pathPatternValues) { this.pathPatternValues = pathPatternValues; }

    public String getHttpHeaderName() { return httpHeaderName; }
    public void setHttpHeaderName(String httpHeaderName) { this.httpHeaderName = httpHeaderName; }

    public List<String> getHttpHeaderValues() { return httpHeaderValues; }
    public void setHttpHeaderValues(List<String> httpHeaderValues) { this.httpHeaderValues = httpHeaderValues; }

    public List<String> getHttpMethodValues() { return httpMethodValues; }
    public void setHttpMethodValues(List<String> httpMethodValues) { this.httpMethodValues = httpMethodValues; }

    public List<String> getSourceIpValues() { return sourceIpValues; }
    public void setSourceIpValues(List<String> sourceIpValues) { this.sourceIpValues = sourceIpValues; }

    public List<QueryStringPair> getQueryStringValues() { return queryStringValues; }
    public void setQueryStringValues(List<QueryStringPair> queryStringValues) { this.queryStringValues = queryStringValues; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QueryStringPair {
        private String key;
        private String value;

        public QueryStringPair() {}

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
