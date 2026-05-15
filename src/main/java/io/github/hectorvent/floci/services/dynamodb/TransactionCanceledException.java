package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.List;

public class TransactionCanceledException extends AwsException {

    public record CancellationReason(String code, JsonNode item) {}

    private final List<CancellationReason> cancellationReasons;

    public TransactionCanceledException(List<CancellationReason> cancellationReasons) {
        super("TransactionCanceledException",
                "Transaction cancelled, please refer cancellation reasons for specific reasons [" +
                        cancellationReasons.stream()
                                .map(r -> r.code().isEmpty() ? "None" : r.code())
                                .reduce((a, b) -> a + ", " + b).orElse("") + "]",
                400);
        this.cancellationReasons = cancellationReasons;
    }

    public List<CancellationReason> getCancellationReasons() {
        return cancellationReasons;
    }
}
