package io.floci.aws.services.lambda;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.lambda.durable.DurableTokens;
import io.floci.aws.services.lambdamicrovms.LambdaMicrovmsService;
import io.floci.aws.services.lambdaweb.LambdaWebService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks Lambda of the resources a request names: the stored layer version a layer version ARN selects, the
 * image the caller's MicroVM runs, which the Service Reference checks an action on a running MicroVM on, the
 * durable execution a callback belongs to, which Lambda authorizes a callback request against, and the tags of a
 * function, an event source mapping, a web function, a MicroVM image or a network connector.
 */
@ApplicationScoped
public class LambdaStoredResourceArns implements StoredResourceArns {

    private final LambdaLayerService layers;
    private final LambdaService functions;
    private final Instance<LambdaMicrovmsService> microvms;
    private final Instance<LambdaWebService> webFunctions;

    @Inject
    public LambdaStoredResourceArns(LambdaLayerService layers, LambdaService functions,
                                    Instance<LambdaMicrovmsService> microvms, Instance<LambdaWebService> webFunctions) {
        this.layers = layers;
        this.functions = functions;
        this.microvms = microvms;
        this.webFunctions = webFunctions;
    }

    @Override
    public String namespace() {
        return "lambda";
    }

    /** Lambda mints no id into an ARN a request names by its name, so there is nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /** The caller's stored layer version a layer version ARN selects, its number written as Lambda parses it. */
    @Override
    public Optional<String> resolveStoredArn(String arn) {
        return layers.storedLayerVersionArn(arn);
    }

    /**
     * The image of the caller's MicroVM a {@code microvmIdentifier} names, and the durable execution of the callback a
     * {@code CallbackId} names; empty when the caller has no such MicroVM, or the id names no callback.
     */
    @Override
    public List<String> through(String member, String value, String region) {
        return switch (member) {
            case "microvmIdentifier" -> microvms.isResolvable()
                    ? microvms.get().findMicrovm(region, value).map(vm -> vm.imageArn)
                            .filter(arn -> !arn.isBlank()).map(List::of).orElse(List.of())
                    : List.of();
            case "CallbackId" -> DurableTokens.callbackExecutionArn(value).map(List::of).orElse(List.of());
            default -> List.of();
        };
    }

    /**
     * The tags of the resource the ARN names, from the service holding it: a web function's, its revisions' and its
     * endpoints' from Lambda Web, a MicroVM image's or a network connector's from Lambda MicroVMs, and a function's,
     * its versions' and aliases', or an event source mapping's from Lambda.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        String resource = AwsArnUtils.resourceIfArnFor(arn, "lambda").orElse("");
        if (resource.startsWith("web-function/")) {
            return webFunctions.isResolvable() ? webFunctions.get().storedTags(arn) : Map.of();
        }
        if (LambdaMicrovmsService.taggedResource(arn).isPresent()) {
            return microvms.isResolvable() ? microvms.get().storedTags(arn) : Map.of();
        }
        return resource.isEmpty() ? Map.of() : functions.storedTags(arn);
    }
}
