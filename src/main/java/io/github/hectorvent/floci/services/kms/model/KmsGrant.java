package io.github.hectorvent.floci.services.kms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KmsGrant {
    private String grantId;
    private String grantToken;
    private String keyId;
    private String keyArn;
    private String granteePrincipal;
    private List<String> operations = new ArrayList<>();
    private long creationDate;

    public KmsGrant() {
        this.creationDate = Instant.now().getEpochSecond();
    }

    public String getGrantId() { return grantId; }
    public void setGrantId(String grantId) { this.grantId = grantId; }

    public String getGrantToken() { return grantToken; }
    public void setGrantToken(String grantToken) { this.grantToken = grantToken; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getKeyArn() { return keyArn; }
    public void setKeyArn(String keyArn) { this.keyArn = keyArn; }

    public String getGranteePrincipal() { return granteePrincipal; }
    public void setGranteePrincipal(String granteePrincipal) { this.granteePrincipal = granteePrincipal; }

    public List<String> getOperations() { return operations; }
    public void setOperations(List<String> operations) { this.operations = operations; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }
}
