package io.floci.aws.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceCredential {
    public SourceCredential() {}

    private String arn;
    private String serverType;
    private String authType;
    private String token;

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getServerType() { return serverType; }
    public void setServerType(String serverType) { this.serverType = serverType; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
