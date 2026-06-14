package io.github.hectorvent.floci.services.appsync.graphql;

import java.util.Set;

public enum AppSyncDirective {
    AWS_API_KEY("aws_api_key", "directive @aws_api_key on OBJECT | FIELD_DEFINITION"),
    AWS_IAM("aws_iam", "directive @aws_iam on OBJECT | FIELD_DEFINITION"),
    AWS_COGNITO_USER_POOLS("aws_cognito_user_pools",
        "directive @aws_cognito_user_pools(cognito_groups: [String!]!) on OBJECT | FIELD_DEFINITION"),
    AWS_OIDC("aws_oidc", "directive @aws_oidc on OBJECT | FIELD_DEFINITION"),
    AWS_LAMBDA("aws_lambda", "directive @aws_lambda on OBJECT | FIELD_DEFINITION"),
    AWS_SUBSCRIBE("aws_subscribe", "directive @aws_subscribe(mutations: [String!]!) on FIELD_DEFINITION"),
    AWS_AUTH("aws_auth", "directive @aws_auth(cognito_groups: [String!]!) on OBJECT"),
    AWS_DELTA_SYNC("aws_delta_sync",
        "directive @aws_delta_sync(tableName: String!, deltaSyncTableTTL: Int!, baseTableTTL: Int!) on OBJECT");

    private static final Set<String> KNOWN_NAMES;
    static {
        java.util.Set<String> names = new java.util.HashSet<>();
        names.add("skip");
        names.add("include");
        names.add("deprecated");
        for (AppSyncDirective d : values()) {
            names.add(d.directiveName());
        }
        KNOWN_NAMES = java.util.Set.copyOf(names);
    }

    private final String name;
    private final String sdl;

    AppSyncDirective(String name, String sdl) {
        this.name = name;
        this.sdl = sdl;
    }

    public String directiveName() {
        return name;
    }

    public String sdl() {
        return sdl;
    }

    public static boolean isKnown(String directiveName) {
        return KNOWN_NAMES.contains(directiveName);
    }
}
