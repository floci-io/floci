package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public class ListPermissionsResponse {
    private String nextToken;
    private List<PrincipalResourcePermissions> principalResourcePermissions;

    public String getNextToken() {
        return nextToken;
    }

    public void setNextToken(String nextToken) {
        this.nextToken = nextToken;
    }

    public List<PrincipalResourcePermissions> getPrincipalResourcePermissions() {
        return principalResourcePermissions;
    }

    public void setPrincipalResourcePermissions(List<PrincipalResourcePermissions> principalResourcePermissions) {
        this.principalResourcePermissions = principalResourcePermissions;
    }
}
