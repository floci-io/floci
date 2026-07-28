package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CloudFormation provisioning for SQS: {@code AWS::SQS::Queue} and {@code AWS::SQS::QueuePolicy}.
 * Extracted verbatim from {@code CloudFormationResourceProvisioner} (item 15 decomposition).
 */
@ApplicationScoped
public class SqsCfnProvisioner implements CfnResourceProvisioner {

    private final SqsService sqsService;

    @Inject
    public SqsCfnProvisioner(SqsService sqsService) {
        this.sqsService = sqsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::SQS::Queue", "AWS::SQS::QueuePolicy");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::SQS::Queue" -> provisionQueue(r, props, ctx);
            case "AWS::SQS::QueuePolicy" -> provisionQueuePolicy(r);
            default -> throw new IllegalStateException("SqsCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if ("AWS::SQS::Queue".equals(resourceType)) {
            sqsService.deleteQueue(physicalId, region);
        }
        // AWS::SQS::QueuePolicy has no backing resource to delete (matches prior behavior).
    }

    private void provisionQueue(StackResource r, JsonNode props, ProvisionContext ctx) {
        String queueName = ctx.resolveOptional(props, "QueueName");
        if (queueName == null || queueName.isBlank()) {
            queueName = ctx.generatePhysicalName(r.getLogicalId(), 80, false);
        }
        Map<String, String> attrs = new HashMap<>();
        if (props != null) {
            if (props.has("VisibilityTimeout")) {
                attrs.put("VisibilityTimeout", ctx.engine().resolve(props.get("VisibilityTimeout")));
            }
            if (props.has("ContentBasedDeduplication")) {
                attrs.put("ContentBasedDeduplication", ctx.engine().resolve(props.get("ContentBasedDeduplication")));
            }
        }
        Queue queue = sqsService.createQueue(queueName, attrs, ctx.region());
        // QueueArn is computed on demand in SqsService#getQueueAttributes and is not stored on the
        // Queue object, so build it here from region + accountId + queueName. Without this,
        // Fn::GetAtt [Queue, Arn] references resolve to an empty string.
        String queueArn = AwsArnUtils.Arn.of("sqs", ctx.region(), ctx.accountId(), queueName).toString();
        r.setPhysicalId(queue.getQueueUrl());
        r.getAttributes().put("Arn", queueArn);
        r.getAttributes().put("QueueName", queueName);
        r.getAttributes().put("QueueUrl", queue.getQueueUrl());
    }

    private void provisionQueuePolicy(StackResource r) {
        r.setPhysicalId("queue-policy-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
