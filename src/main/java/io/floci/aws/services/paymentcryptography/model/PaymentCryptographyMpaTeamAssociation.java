package io.floci.aws.services.paymentcryptography.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A Multi-Party Approval team associated with a protected operation.
 *
 * <p>The association is the account's, one per protected operation: a second association
 * of the same operation replaces the first. The emulator keeps the team's ARN but has no
 * MPA service to check it against, so an ARN the model accepts is stored as it came.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentCryptographyMpaTeamAssociation {

    private String action;
    private String region;
    private String mpaTeamArn;
    private String associationState = "ACTIVE";

    public PaymentCryptographyMpaTeamAssociation() {
    }

    public PaymentCryptographyMpaTeamAssociation(String action, String region, String mpaTeamArn,
                                                 String associationState) {
        this.action = action;
        this.region = region;
        this.mpaTeamArn = mpaTeamArn;
        this.associationState = associationState;
    }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getMpaTeamArn() { return mpaTeamArn; }
    public void setMpaTeamArn(String mpaTeamArn) { this.mpaTeamArn = mpaTeamArn; }

    public String getAssociationState() { return associationState; }
    public void setAssociationState(String associationState) {
        this.associationState = associationState;
    }
}
