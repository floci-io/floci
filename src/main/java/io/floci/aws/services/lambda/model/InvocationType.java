package io.floci.aws.services.lambda.model;

public enum InvocationType {
    RequestResponse,
    Event,
    DryRun;

    public static InvocationType parse(String value) {
        if (value == null || value.isBlank()) {
            return RequestResponse;
        }
        return switch (value) {
            case "Event" -> Event;
            case "DryRun" -> DryRun;
            default -> RequestResponse;
        };
    }
}
