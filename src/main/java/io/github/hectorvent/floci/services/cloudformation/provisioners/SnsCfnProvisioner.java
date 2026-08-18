package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CloudFormation provisioning for {@code AWS::SNS::TopicPolicy} (aws-bench gap batch, issue #17).
 * {@code AWS::SNS::Topic} and {@code AWS::SNS::Subscription} remain on the legacy
 * {@code CloudFormationResourceProvisioner} switch for now; only TopicPolicy is extracted here.
 */
@ApplicationScoped
public class SnsCfnProvisioner implements CfnResourceProvisioner {

    private final SnsService snsService;

    @Inject
    public SnsCfnProvisioner(SnsService snsService) {
        this.snsService = snsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::SNS::TopicPolicy");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // The CFN property is "Topics" (a list of topic ARNs), not "TopicArns".
        List<String> topicArns = ctx.resolveStringList(props, "Topics");
        if (topicArns.isEmpty()) {
            throw new AwsException("ValidationError",
                    "AWS::SNS::TopicPolicy requires at least one topic ARN in Topics", 400);
        }
        JsonNode policyNode = props != null ? props.get("PolicyDocument") : null;
        String policyDocument = policyNode != null && !policyNode.isNull()
                ? ctx.engine().resolveNode(policyNode).toString()
                : "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

        // This resource creates no AWS resource of its own; it mutates the Policy attribute of
        // already-created topics. Re-applying on UpdateStack is idempotent.
        for (String topicArn : topicArns) {
            snsService.setTopicAttributes(topicArn, "Policy", policyDocument, ctx.region());
        }

        if (r.getPhysicalId() == null) {
            r.setPhysicalId("topic-policy-" + UUID.randomUUID().toString().substring(0, 8));
        }
        r.getAttributes().put("Id", r.getPhysicalId());
    }

    // No backing delete: AWS::SNS::TopicPolicy has no resource of its own to remove, matching the
    // precedent set by AWS::SQS::QueuePolicy (SqsCfnProvisioner) and AWS::S3::BucketPolicy.
}
