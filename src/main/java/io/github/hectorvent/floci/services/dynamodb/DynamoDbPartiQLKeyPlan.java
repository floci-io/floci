package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Cond;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.PVal;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Path;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

final class DynamoDbPartiQLKeyPlan {

    private final String partitionKey;
    private final String sortKey;
    private final TableDefinition table;
    private final List<Branch> branches;

    private record Branch(List<Cond.Leaf> keyConditions, List<Cond> filters) {

        Branch and(Branch other) {
            return new Branch(Stream.concat(keyConditions.stream(), other.keyConditions.stream()).toList(),
                    Stream.concat(filters.stream(), other.filters.stream()).toList());
        }
    }

    private DynamoDbPartiQLKeyPlan(List<Cond> where, DynamoDbAccessPath accessPath, TableDefinition table) {
        this.partitionKey = accessPath.partitionKeyName();
        this.sortKey = accessPath.sortKeyName();
        this.table = table;
        List<Branch> spread = List.of(new Branch(List.of(), List.of()));
        for (Cond cond : where) {
            spread = and(spread, spread(cond));
        }
        this.branches = spread;
    }

    static DynamoDbPartiQLKeyPlan of(List<Cond> where, DynamoDbAccessPath accessPath, TableDefinition table) {
        return new DynamoDbPartiQLKeyPlan(where, accessPath, table);
    }

    void requireKeyTypesMatchSchema() {
        for (Branch branch : branches) {
            for (Cond.Leaf condition : branch.keyConditions()) {
                String name = condition.path().root();
                for (PVal value : values(condition)) {
                    String type = DynamoDbPartiQLParser.typeCode(value);
                    if (!Set.of("S", "N", "B").contains(type)) {
                        throw validationEx("Key value must be of type S, N, or B. Key name: " + name + ", Key type: " + type);
                    }
                    if (!matchesKeyType(table, name, value)) {
                        throw validationEx("Key attribute's data type should match its data type in table's schema: Key " + name);
                    }
                }
            }
        }
    }

    static boolean matchesKeyType(TableDefinition table, String keyName, PVal value) {
        return table.getAttributeDefinitions().stream()
                .filter(definition -> definition.getAttributeName().equals(keyName))
                .map(AttributeDefinition::getAttributeType)
                .anyMatch(DynamoDbPartiQLParser.typeCode(value)::equals);
    }

    void requireNoOverlap() {
        if (!keyed()) {
            return;
        }
        for (int i = 0; i < branches.size(); i++) {
            for (int j = i + 1; j < branches.size(); j++) {
                boolean samePartition = compare(partitionOf(branches.get(i)), partitionOf(branches.get(j))) == 0;
                if (samePartition && sortRangeOf(branches.get(i)).overlaps(sortRangeOf(branches.get(j)))) {
                    throw validationEx("Overlapping conditions with range keys are not supported in where clause");
                }
            }
        }
    }

    List<String> unprojectedFilterAttributes(Set<String> projected) {
        if (!keyed()) {
            return List.of();
        }
        List<Set<String>> unprojected = new ArrayList<>();
        for (Branch branch : branches.stream().sorted(Comparator.comparing(this::partitionOf, DynamoDbPartiQLKeyPlan::compare)).toList()) {
            Set<String> names = new HashSet<>();
            branch.filters().stream()
                    .flatMap(DynamoDbPartiQLKeyPlan::attributePaths)
                    .map(Path::root)
                    .filter(root -> !projected.contains(root))
                    .forEach(names::add);
            if (names.isEmpty()) {
                return List.of();
            }
            unprojected.add(names);
        }
        return List.copyOf(unprojected.getLast());
    }

    Optional<PVal> singlePartition() {
        if (!keyed() || branches.stream().anyMatch(branch -> compare(partitionOf(branch), partitionOf(branches.getFirst())) != 0)) {
            return Optional.empty();
        }
        return Optional.of(partitionOf(branches.getFirst()));
    }

    static Stream<Path> attributePaths(Cond cond) {
        return switch (cond) {
            case Cond.Leaf leaf -> Stream.of(leaf.path());
            case Cond.Not not   -> attributePaths(not.operand());
            case Cond.And and   -> and.operands().stream().flatMap(DynamoDbPartiQLKeyPlan::attributePaths);
            case Cond.Or or     -> or.operands().stream().flatMap(DynamoDbPartiQLKeyPlan::attributePaths);
        };
    }

    private boolean keyed() {
        return branches.stream().allMatch(branch -> partitionValues(branch).size() == 1);
    }

    private PVal partitionOf(Branch branch) {
        return partitionValues(branch).getFirst();
    }

    private List<PVal> partitionValues(Branch branch) {
        List<PVal> distinct = new ArrayList<>();
        branch.keyConditions().stream()
                .filter(condition -> condition.path().root().equals(partitionKey))
                .map(condition -> ((Cond.Eq) condition).val())
                .filter(value -> distinct.stream().noneMatch(seen -> compare(seen, value) == 0))
                .forEach(distinct::add);
        return distinct;
    }

    private Range sortRangeOf(Branch branch) {
        Range range = Range.ALL;
        for (Cond.Leaf condition : branch.keyConditions()) {
            if (condition.path().root().equals(sortKey)) {
                range = range.intersect(rangeOf(condition));
            }
        }
        return range;
    }

    private List<Branch> spread(Cond cond) {
        return switch (cond) {
            case Cond.In in when isKeyCondition(in) -> in.values().stream()
                    .map(value -> new Branch(List.of(new Cond.Eq(in.path(), value)), List.of()))
                    .toList();
            case Cond.Leaf leaf when isKeyCondition(leaf) -> List.of(new Branch(List.of(leaf), List.of()));
            case Cond.And and -> {
                List<Branch> spread = List.of(new Branch(List.of(), List.of()));
                for (Cond operand : and.operands()) {
                    spread = and(spread, spread(operand));
                }
                yield spread;
            }
            case Cond.Or or when namesKey(or) -> or.operands().stream().flatMap(operand -> spread(operand).stream()).toList();
            default -> List.of(new Branch(List.of(), List.of(cond)));
        };
    }

    private static List<Branch> and(List<Branch> left, List<Branch> right) {
        return left.stream().flatMap(l -> right.stream().map(l::and)).toList();
    }

    private boolean namesKey(Cond cond) {
        return switch (cond) {
            case Cond.Leaf leaf -> isKeyCondition(leaf);
            case Cond.And and   -> and.operands().stream().anyMatch(this::namesKey);
            case Cond.Or or     -> or.operands().stream().anyMatch(this::namesKey);
            case Cond.Not ignored -> false;
        };
    }

    private boolean isKeyCondition(Cond.Leaf leaf) {
        Optional<String> attribute = leaf.bareAttribute();
        if (attribute.filter(partitionKey::equals).isPresent()) {
            return leaf instanceof Cond.Eq || leaf instanceof Cond.In;
        }
        if (sortKey == null || attribute.filter(sortKey::equals).isEmpty()) {
            return false;
        }
        if (leaf instanceof Cond.Cmp cmp) {
            return !"<>".equals(cmp.op());
        }
        return leaf instanceof Cond.Eq || leaf instanceof Cond.Between || leaf instanceof Cond.BeginsWith
                || leaf instanceof Cond.In;
    }

    private static List<PVal> values(Cond.Leaf condition) {
        return switch (condition) {
            case Cond.Eq eq -> List.of(eq.val());
            case Cond.Cmp cmp -> List.of(cmp.val());
            case Cond.Between between -> List.of(between.lo(), between.hi());
            case Cond.BeginsWith beginsWith -> List.of(beginsWith.prefix());
            case Cond.In in -> in.values();
            default -> List.of();
        };
    }

    private Range rangeOf(Cond.Leaf condition) {
        return switch (condition) {
            case Cond.Eq eq -> new Range(eq.val(), true, eq.val(), true);
            case Cond.Between between -> new Range(between.lo(), true, between.hi(), true);
            case Cond.BeginsWith beginsWith -> new Range(beginsWith.prefix(), true, prefixEnd(beginsWith.prefix()), false);
            case Cond.Cmp cmp -> switch (cmp.op()) {
                case "<" -> new Range(null, false, cmp.val(), false);
                case "<=" -> new Range(null, false, cmp.val(), true);
                case ">" -> new Range(cmp.val(), false, null, false);
                default -> new Range(cmp.val(), true, null, false);
            };
            default -> Range.ALL;
        };
    }

    private static PVal prefixEnd(PVal prefix) {
        if (!(prefix instanceof PVal.Str text)) {
            return null;
        }
        byte[] bytes = text.v().getBytes(StandardCharsets.UTF_8);
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] != (byte) 0xFF) {
                byte[] end = Arrays.copyOf(bytes, i + 1);
                end[i]++;
                return new PVal.Av("B", JsonNodeFactory.instance.objectNode().put("B", Base64.getEncoder().encodeToString(end)));
            }
        }
        return null;
    }

    private static int compare(PVal left, PVal right) {
        if (left instanceof PVal.Num a && right instanceof PVal.Num b) {
            return new BigDecimal(a.v()).compareTo(new BigDecimal(b.v()));
        }
        return Arrays.compareUnsigned(bytesOf(left), bytesOf(right));
    }

    private static byte[] bytesOf(PVal value) {
        return switch (value) {
            case PVal.Str text -> text.v().getBytes(StandardCharsets.UTF_8);
            case PVal.Av av when av.node().has("B") -> Base64.getDecoder().decode(av.node().get("B").asText());
            default -> DynamoDbPartiQLParser.typeCode(value).getBytes(StandardCharsets.UTF_8);
        };
    }

    private record Range(PVal lower, boolean lowerInclusive, PVal upper, boolean upperInclusive) {

        static final Range ALL = new Range(null, false, null, false);

        Range intersect(Range other) {
            PVal newLower = lower;
            boolean newLowerInclusive = lowerInclusive;
            if (other.lower != null && (lower == null || compare(other.lower, lower) > 0
                    || (compare(other.lower, lower) == 0 && !other.lowerInclusive))) {
                newLower = other.lower;
                newLowerInclusive = other.lowerInclusive;
            }
            PVal newUpper = upper;
            boolean newUpperInclusive = upperInclusive;
            if (other.upper != null && (upper == null || compare(other.upper, upper) < 0
                    || (compare(other.upper, upper) == 0 && !other.upperInclusive))) {
                newUpper = other.upper;
                newUpperInclusive = other.upperInclusive;
            }
            return new Range(newLower, newLowerInclusive, newUpper, newUpperInclusive);
        }

        boolean overlaps(Range other) {
            Range both = intersect(other);
            if (both.lower == null || both.upper == null) {
                return true;
            }
            int order = compare(both.lower, both.upper);
            return order < 0 || (order == 0 && both.lowerInclusive && both.upperInclusive);
        }
    }

    private static AwsException validationEx(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
