package io.github.hectorvent.floci.services.redshift;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class RedshiftIamDbUserResolver {

    /** Placeholder until Task 6: returns the fallback IAM role DB user. */
    public String resolveDbUser(String authorizationHeader) {
        return "IAMR:floci";
    }
}
