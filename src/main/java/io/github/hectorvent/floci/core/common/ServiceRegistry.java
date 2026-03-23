package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of enabled AWS services based on configuration.
 */
@ApplicationScoped
public class ServiceRegistry {

    private static final Logger LOG = Logger.getLogger(ServiceRegistry.class);

    private final EmulatorConfig config;

    @Inject
    public ServiceRegistry(EmulatorConfig config) {
        this.config = config;
    }

    public boolean isServiceEnabled(String serviceName) {
        return switch (serviceName) {
            case "ssm" -> config.services().ssm().enabled();
            case "sqs" -> config.services().sqs().enabled();
            case "s3" -> config.services().s3().enabled();
            case "dynamodb" -> config.services().dynamodb().enabled();
            case "sns" -> config.services().sns().enabled();
            case "lambda" -> config.services().lambda().enabled();
            case "apigateway" -> config.services().apigateway().enabled();
            case "iam", "sts" -> config.services().iam().enabled();
            case "elasticache" -> config.services().elasticache().enabled();
            case "rds" -> config.services().rds().enabled();
            case "events" -> config.services().eventbridge().enabled();
            case "logs" -> config.services().cloudwatchlogs().enabled();
            case "monitoring" -> config.services().cloudwatchmetrics().enabled();
            case "secretsmanager" -> config.services().secretsmanager().enabled();
            case "apigatewayv2" -> config.services().apigatewayv2().enabled();
            case "kinesis" -> config.services().kinesis().enabled();
            case "kms" -> config.services().kms().enabled();
            case "cognito-idp" -> config.services().cognito().enabled();
            case "states" -> config.services().stepfunctions().enabled();
            case "cloudformation" -> config.services().cloudformation().enabled();
            case "acm" -> config.services().acm().enabled();
            default -> true;
        };
    }

    public List<String> getEnabledServices() {
        List<String> enabled = new ArrayList<>();
        if (config.services().ssm().enabled()) enabled.add("ssm");
        if (config.services().sqs().enabled()) enabled.add("sqs");
        if (config.services().s3().enabled()) enabled.add("s3");
        if (config.services().dynamodb().enabled()) enabled.add("dynamodb");
        if (config.services().sns().enabled()) enabled.add("sns");
        if (config.services().lambda().enabled()) enabled.add("lambda");
        if (config.services().apigateway().enabled()) enabled.add("apigateway");
        if (config.services().iam().enabled()) enabled.add("iam");
        if (config.services().elasticache().enabled()) enabled.add("elasticache");
        if (config.services().rds().enabled()) enabled.add("rds");
        if (config.services().eventbridge().enabled()) enabled.add("events");
        if (config.services().cloudwatchlogs().enabled()) enabled.add("logs");
        if (config.services().cloudwatchmetrics().enabled()) enabled.add("monitoring");
        if (config.services().secretsmanager().enabled()) enabled.add("secretsmanager");
        if (config.services().apigatewayv2().enabled()) enabled.add("apigatewayv2");
        if (config.services().kinesis().enabled()) enabled.add("kinesis");
        if (config.services().kms().enabled()) enabled.add("kms");
        if (config.services().cognito().enabled()) enabled.add("cognito-idp");
        if (config.services().stepfunctions().enabled()) enabled.add("states");
        if (config.services().cloudformation().enabled()) enabled.add("cloudformation");
        if (config.services().acm().enabled()) enabled.add("acm");
        return enabled;
    }

    public void logEnabledServices() {
        LOG.infov("Enabled services: {0}", getEnabledServices());
    }
}
