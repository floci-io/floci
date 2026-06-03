package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KinesisFirehoseDestination {

    @JsonProperty("IamRoleArn")
    private String iamRoleArn;

    @JsonProperty("DeliveryStreamArn")
    private String deliveryStreamArn;

    public KinesisFirehoseDestination() {}

    public String getIamRoleArn() { return iamRoleArn; }
    public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }

    public String getDeliveryStreamArn() { return deliveryStreamArn; }
    public void setDeliveryStreamArn(String deliveryStreamArn) { this.deliveryStreamArn = deliveryStreamArn; }
}
