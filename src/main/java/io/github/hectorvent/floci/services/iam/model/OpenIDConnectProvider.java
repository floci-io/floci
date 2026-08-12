package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An IAM OIDC identity provider.
 *
 * <p>{@link #url} is stored without its scheme, which is how AWS reports it: a provider created
 * from {@code https://oidc.eks.eu-central-1.amazonaws.com/id/EXAMPLE} reads back as
 * {@code oidc.eks.eu-central-1.amazonaws.com/id/EXAMPLE}, and the ARN is built from that same
 * scheme-less form.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenIDConnectProvider {

    private String arn;
    private String url;
    private List<String> clientIdList = new ArrayList<>();
    private List<String> thumbprintList = new ArrayList<>();
    private Instant createDate = Instant.now();
    private Map<String, String> tags = new ConcurrentHashMap<>();

    public OpenIDConnectProvider() {}

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public List<String> getClientIdList() { return clientIdList; }
    public void setClientIdList(List<String> clientIdList) { this.clientIdList = clientIdList; }

    public List<String> getThumbprintList() { return thumbprintList; }
    public void setThumbprintList(List<String> thumbprintList) { this.thumbprintList = thumbprintList; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = new ConcurrentHashMap<>(tags); }
}
