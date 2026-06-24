package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelNamespace {
    private String name;
    private String apiId;
    private String description;
    private String channelNamespaceArn;
    private String codeHandlers;
    private Long created;
    private HandlerConfigs handlerConfigs;
    private Long lastModified;
    private List<AuthMode> publishAuthModes;
    private List<AuthMode> subscribeAuthModes;
    private Map<String, String> tags;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getApiId() { return apiId; }
    public void setApiId(String apiId) { this.apiId = apiId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getChannelNamespaceArn() { return channelNamespaceArn; }
    public void setChannelNamespaceArn(String channelNamespaceArn) { this.channelNamespaceArn = channelNamespaceArn; }

    public String getCodeHandlers() { return codeHandlers; }
    public void setCodeHandlers(String codeHandlers) { this.codeHandlers = codeHandlers; }

    public Long getCreated() { return created; }
    public void setCreated(Long created) { this.created = created; }

    public HandlerConfigs getHandlerConfigs() { return handlerConfigs; }
    public void setHandlerConfigs(HandlerConfigs handlerConfigs) { this.handlerConfigs = handlerConfigs; }

    public Long getLastModified() { return lastModified; }
    public void setLastModified(Long lastModified) { this.lastModified = lastModified; }

    public List<AuthMode> getPublishAuthModes() { return publishAuthModes; }
    public void setPublishAuthModes(List<AuthMode> publishAuthModes) { this.publishAuthModes = publishAuthModes; }

    public List<AuthMode> getSubscribeAuthModes() { return subscribeAuthModes; }
    public void setSubscribeAuthModes(List<AuthMode> subscribeAuthModes) { this.subscribeAuthModes = subscribeAuthModes; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
