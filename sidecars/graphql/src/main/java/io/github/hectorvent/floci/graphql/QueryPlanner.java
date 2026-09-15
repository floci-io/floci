package io.github.hectorvent.floci.graphql;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.TypeName;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Statically walks a query's selection set against a schema to list every {@code (typeName,
 * fieldName)} coordinate it could visit, plus the directives applied to each, with zero
 * interpretation of what any directive means. Used so Floci can precompute field-level
 * authorization decisions before calling {@code /v1/execute}, instead of the sidecar knowing
 * anything about {@code @aws_auth}/IAM (see #2917).
 *
 * <p>Because resolver -> data-source dispatch isn't implemented (fields resolve to nulls
 * either way), an interface/union is expanded to every possible concrete type rather than a
 * single resolved one, the same "list every candidate" stance as {@code GraphqlSidecarServer}'s
 * placeholder {@code TypeResolver}.
 */
final class QueryPlanner {

    record AppliedDirective(String name, Map<String, Object> args) {
    }

    /**
     * {@code typeDirectives} are the containing object type's own applied directives (an
     * AppSync {@code @aws_auth} etc. can be declared on the type instead of the field),
     * carried alongside so Floci can apply the same "field directives, else type directives"
     * fallback its in-process authorizer already uses, without a second round trip.
     */
    record VisitedField(String typeName, String fieldName, List<AppliedDirective> directives,
                         List<AppliedDirective> typeDirectives) {
    }

    private QueryPlanner() {
    }

    static List<VisitedField> plan(GraphQLSchema schema, Document document, OperationDefinition operation) {
        GraphQLObjectType rootType = rootType(schema, operation);
        Map<String, FragmentDefinition> fragments = new HashMap<>();
        document.getDefinitionsOfType(FragmentDefinition.class)
                .forEach(fragment -> fragments.put(fragment.getName(), fragment));

        List<VisitedField> visited = new ArrayList<>();
        Set<String> seenCoordinates = new LinkedHashSet<>();
        walk(operation.getSelectionSet(), List.of(rootType), schema, fragments, visited, seenCoordinates, Set.of());
        return visited;
    }

    private static GraphQLObjectType rootType(GraphQLSchema schema, OperationDefinition operation) {
        GraphQLObjectType root = switch (operation.getOperation()) {
            case MUTATION -> schema.getMutationType();
            case SUBSCRIPTION -> schema.getSubscriptionType();
            default -> schema.getQueryType();
        };
        if (root == null) {
            throw new IllegalArgumentException("Schema has no " + operation.getOperation() + " root type.");
        }
        return root;
    }

    private static void walk(SelectionSet selectionSet, List<GraphQLObjectType> possibleTypes, GraphQLSchema schema,
                             Map<String, FragmentDefinition> fragments, List<VisitedField> visited,
                             Set<String> seenCoordinates, Set<String> fragmentsInProgress) {
        if (selectionSet == null) {
            return;
        }
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                walkField(field, possibleTypes, schema, fragments, visited, seenCoordinates, fragmentsInProgress);
            } else if (selection instanceof InlineFragment inline) {
                List<GraphQLObjectType> narrowed = inline.getTypeCondition() == null
                        ? possibleTypes : narrow(possibleTypes, inline.getTypeCondition(), schema);
                walk(inline.getSelectionSet(), narrowed, schema, fragments, visited, seenCoordinates, fragmentsInProgress);
            } else if (selection instanceof FragmentSpread spread) {
                if (fragmentsInProgress.contains(spread.getName())) {
                    throw new IllegalArgumentException("Fragment cycle detected at " + spread.getName());
                }
                FragmentDefinition fragment = fragments.get(spread.getName());
                if (fragment == null) {
                    throw new IllegalArgumentException("Unknown fragment: " + spread.getName());
                }
                List<GraphQLObjectType> narrowed = narrow(possibleTypes, fragment.getTypeCondition(), schema);
                Set<String> nextInProgress = new HashSet<>(fragmentsInProgress);
                nextInProgress.add(spread.getName());
                walk(fragment.getSelectionSet(), narrowed, schema, fragments, visited, seenCoordinates, nextInProgress);
            }
        }
    }

    private static void walkField(Field field, List<GraphQLObjectType> possibleTypes, GraphQLSchema schema,
                                  Map<String, FragmentDefinition> fragments, List<VisitedField> visited,
                                  Set<String> seenCoordinates, Set<String> fragmentsInProgress) {
        if (field.getName().startsWith("__")) {
            return;
        }
        for (GraphQLObjectType type : possibleTypes) {
            GraphQLFieldDefinition definition = type.getFieldDefinition(field.getName());
            if (definition == null) {
                continue;
            }
            String coordinate = type.getName() + "." + field.getName();
            if (seenCoordinates.add(coordinate)) {
                visited.add(new VisitedField(type.getName(), field.getName(),
                        directivesOf(definition.getAppliedDirectives()), directivesOf(type.getAppliedDirectives())));
            }
            if (field.getSelectionSet() != null) {
                List<GraphQLObjectType> nested = expand(definition.getType(), schema);
                walk(field.getSelectionSet(), nested, schema, fragments, visited, seenCoordinates, fragmentsInProgress);
            }
        }
    }

    private static List<AppliedDirective> directivesOf(List<GraphQLAppliedDirective> applied) {
        List<AppliedDirective> directives = new ArrayList<>();
        for (GraphQLAppliedDirective directive : applied) {
            Map<String, Object> args = new HashMap<>();
            for (GraphQLAppliedDirectiveArgument arg : directive.getArguments()) {
                args.put(arg.getName(), arg.getValue());
            }
            directives.add(new AppliedDirective(directive.getName(), args));
        }
        return directives;
    }

    private static List<GraphQLObjectType> narrow(List<GraphQLObjectType> possibleTypes, TypeName condition,
                                                   GraphQLSchema schema) {
        Set<GraphQLObjectType> conditionTypes = new LinkedHashSet<>(typesMatching(condition.getName(), schema));
        List<GraphQLObjectType> narrowed = new ArrayList<>();
        for (GraphQLObjectType type : possibleTypes) {
            if (conditionTypes.contains(type)) {
                narrowed.add(type);
            }
        }
        return narrowed;
    }

    private static List<GraphQLObjectType> typesMatching(String typeName, GraphQLSchema schema) {
        GraphQLType type = schema.getType(typeName);
        return expandType(type, schema);
    }

    private static List<GraphQLObjectType> expand(GraphQLOutputType type, GraphQLSchema schema) {
        return expandType(GraphQLTypeUtil.unwrapAll(type), schema);
    }

    private static List<GraphQLObjectType> expandType(GraphQLType type, GraphQLSchema schema) {
        if (type instanceof GraphQLObjectType objectType) {
            return List.of(objectType);
        }
        if (type instanceof GraphQLInterfaceType interfaceType) {
            return schema.getImplementations(interfaceType);
        }
        if (type instanceof GraphQLUnionType unionType) {
            return unionType.getTypes().stream()
                    .filter(GraphQLObjectType.class::isInstance)
                    .map(GraphQLObjectType.class::cast)
                    .toList();
        }
        return List.of();
    }
}
