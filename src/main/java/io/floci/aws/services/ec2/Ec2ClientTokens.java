package io.floci.aws.services.ec2;

import io.floci.aws.core.common.AwsException;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** ClientToken replays, for the EC2 calls that return what an earlier call with the same token made. */
final class Ec2ClientTokens {

    private Ec2ClientTokens() {
    }

    /**
     * The resource an earlier create made with {@code clientToken}: the first of {@code candidates} that carries the
     * token, or empty when the token is unset or unused. The candidates are only read when a token is set. A reused
     * token whose request differs from the first one ({@code sameRequest} false) fails with
     * IdempotentParameterMismatch, worded {@code mismatch}, as the EC2 idempotency guide documents.
     */
    static <T> Optional<T> replay(String clientToken, Supplier<Stream<T>> candidates, Function<T, String> tokenOf,
                                  Predicate<T> sameRequest, String mismatch) {
        if (clientToken == null || clientToken.isBlank()) {
            return Optional.empty();
        }
        Optional<T> first = candidates.get().filter(candidate -> clientToken.equals(tokenOf.apply(candidate)))
                .findFirst();
        if (first.isPresent() && !sameRequest.test(first.get())) {
            throw new AwsException("IdempotentParameterMismatch", mismatch, 400);
        }
        return first;
    }

    /** As above, for a create whose replay is not compared with the first request. */
    static <T> Optional<T> replay(String clientToken, Supplier<Stream<T>> candidates, Function<T, String> tokenOf) {
        return replay(clientToken, candidates, tokenOf, first -> true, null);
    }
}
