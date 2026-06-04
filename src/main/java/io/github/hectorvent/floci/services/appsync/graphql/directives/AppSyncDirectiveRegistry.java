package io.github.hectorvent.floci.services.appsync.graphql.directives;

import graphql.introspection.Introspection;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLScalarType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;

@ApplicationScoped
public class AppSyncDirectiveRegistry {

    public static final GraphQLDirective AWS_API_KEY = GraphQLDirective.newDirective()
        .name("aws_api_key")
        .validLocation(Introspection.DirectiveLocation.OBJECT)
        .validLocation(Introspection.DirectiveLocation.FIELD_DEFINITION)
        .build();

    public static final GraphQLDirective AWS_IAM = GraphQLDirective.newDirective()
        .name("aws_iam")
        .validLocation(Introspection.DirectiveLocation.OBJECT)
        .validLocation(Introspection.DirectiveLocation.FIELD_DEFINITION)
        .build();

    public static final GraphQLDirective AWS_COGNITO_USER_POOLS = GraphQLDirective.newDirective()
        .name("aws_cognito_user_pools")
        .validLocation(Introspection.DirectiveLocation.OBJECT)
        .validLocation(Introspection.DirectiveLocation.FIELD_DEFINITION)
        .argument(newArgument()
            .name("cognito_groups")
            .type(nonNull(list(nonNull(GraphQLString))))
            .build())
        .build();

    public static final GraphQLDirective AWS_OIDC = GraphQLDirective.newDirective()
        .name("aws_oidc")
        .validLocation(Introspection.DirectiveLocation.OBJECT)
        .validLocation(Introspection.DirectiveLocation.FIELD_DEFINITION)
        .build();

    public static final GraphQLDirective AWS_LAMBDA = GraphQLDirective.newDirective()
        .name("aws_lambda")
        .validLocation(Introspection.DirectiveLocation.OBJECT)
        .validLocation(Introspection.DirectiveLocation.FIELD_DEFINITION)
        .build();

    public static final GraphQLDirective AWS_SUBSCRIBE = GraphQLDirective.newDirective()
        .name("aws_subscribe")
        .validLocation(Introspection.DirectiveLocation.FIELD_DEFINITION)
        .argument(newArgument()
            .name("mutations")
            .type(nonNull(list(nonNull(GraphQLString))))
            .build())
        .build();

    public static final GraphQLDirective AWS_AUTH = GraphQLDirective.newDirective()
        .name("aws_auth")
        .validLocation(Introspection.DirectiveLocation.OBJECT)
        .argument(newArgument()
            .name("cognito_groups")
            .type(nonNull(list(nonNull(GraphQLString))))
            .build())
        .build();

    public static final GraphQLDirective AWS_DELTA_SYNC = GraphQLDirective.newDirective()
        .name("aws_delta_sync")
        .validLocation(Introspection.DirectiveLocation.OBJECT)
        .argument(newArgument()
            .name("tableName")
            .type(nonNull(GraphQLString))
            .build())
        .argument(newArgument()
            .name("deltaSyncTableTTL")
            .type(nonNull(graphql.Scalars.GraphQLInt))
            .build())
        .argument(newArgument()
            .name("baseTableTTL")
            .type(nonNull(graphql.Scalars.GraphQLInt))
            .build())
        .build();

    public List<GraphQLDirective> allDirectives() {
        return List.of(
            AWS_API_KEY,
            AWS_IAM,
            AWS_COGNITO_USER_POOLS,
            AWS_OIDC,
            AWS_LAMBDA,
            AWS_SUBSCRIBE,
            AWS_AUTH,
            AWS_DELTA_SYNC
        );
    }
}
