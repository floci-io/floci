package io.github.hectorvent.floci.services.identitystore.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Membership {
    private String membershipId;
    private String identityStoreId;
    private String groupId;
    private String userId;
    private String createdAt;
    private String updatedAt;

    public Membership() {
    }

    public Membership(String membershipId, String identityStoreId, String groupId, String userId,
                      String createdAt, String updatedAt) {
        this.membershipId = membershipId;
        this.identityStoreId = identityStoreId;
        this.groupId = groupId;
        this.userId = userId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String membershipId() {
        return membershipId;
    }

    public String identityStoreId() {
        return identityStoreId;
    }

    public String groupId() {
        return groupId;
    }

    public String userId() {
        return userId;
    }

    public String createdAt() {
        return createdAt;
    }

    public String updatedAt() {
        return updatedAt;
    }
}
