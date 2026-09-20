package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Cond;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.PVal;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Path;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class DynamoDbPartiQLKeyPlan {

    private static final int MAX_BRANCHES = 1000;
    private static final int MAX_DECOMPOSED_READS = 50;

    private final String partitionKey;
    private final String sortKey;
    private final TableDefinition table;
    private final boolean everyBranchPinsPartition;
    private final List<Cond.Leaf> keyConditions = new ArrayList<>();
    private final List<Branch> branches;
    private boolean tooManyBranches;

    private record Spread(List<Cond.Leaf> keyConditions, List<Cond> filters) {

        static final Spread EMPTY = new Spread(List.of(), List.of());

        Spread and(Spread other) {
            return new Spread(Stream.concat(keyConditions.stream(), other.keyConditions.stream()).toList(),
                    Stream.concat(filters.stream(), other.filters.stream()).toList());
        }
    }

    private record Branch(Key partition, PVal partitionValue, Range sortRange, List<Cond> filters) {}

    DynamoDbPartiQLKeyPlan(List<Cond> where, DynamoDbAccessPath accessPath, TableDefinition table) {
        this.partitionKey = accessPath.partitionKeyName();
        this.sortKey = accessPath.sortKeyName();
        this.table = table;
        Cond conditions = new Cond.And(where);
        this.everyBranchPinsPartition = everyBranchPinsPartition(conditions);
        List<Spread> spread = spread(conditions);
        this.branches = tooManyBranches
                ? List.of(new Branch(null, null, Range.ALL, where))
                : spread.stream().map(this::branchOf).toList();
    }

    void requireKeyTypesMatchSchema() {
        for (Cond.Leaf condition : keyConditions) {
            String name = condition.path().root();
            for (PVal value : values(condition)) {
                String type = DynamoDbPartiQLParser.typeCode(value);
                if (!DynamoDbPartiQLParser.ORDERED_TYPES.contains(type)) {
                    throw DynamoDbPartiQLParser.validationEx(
                            "Key value must be of type S, N, or B. Key name: " + name + ", Key type: " + type);
                }
                if (!matchesKeyType(table, name, value)) {
                    throw DynamoDbPartiQLParser.validationEx(
                            "Key attribute's data type should match its data type in table's schema: Key " + name);
                }
            }
        }
    }

    static boolean matchesKeyType(TableDefinition table, String keyName, PVal value) {
        return DynamoDbAccessPathValidator.attributeType(table, keyName).equals(DynamoDbPartiQLParser.typeCode(value));
    }

    void requireReadsWithinLimit() {
        if (everyBranchPinsPartition && (tooManyBranches || branches.size() > MAX_DECOMPOSED_READS)) {
            throw DynamoDbPartiQLParser.validationEx("Too many decomposed read operations for a given query.");
        }
    }

    private boolean everyBranchPinsPartition(Cond cond) {
        return switch (cond) {
            case Cond.Leaf leaf -> pinsPartition(leaf);
            case Cond.And and -> and.operands().stream().anyMatch(this::everyBranchPinsPartition);
            case Cond.Or or   -> or.operands().stream().allMatch(this::everyBranchPinsPartition);
            case Cond.Not ignored -> false;
        };
    }

    void requireNoOverlap() {
        if (!keyed()) {
            return;
        }
        Map<Key, List<Range>> rangesByPartition = new TreeMap<>();
        for (Branch branch : branches) {
            rangesByPartition.computeIfAbsent(branch.partition(), ignored -> new ArrayList<>()).add(branch.sortRange());
        }
        for (List<Range> ranges : rangesByPartition.values()) {
            Range reach = null;
            for (Range range : ranges.stream().filter(range -> !range.isEmpty()).sorted(Range.BY_LOWER).toList()) {
                if (reach != null && range.startsBefore(reach)) {
                    throw DynamoDbPartiQLParser.validationEx(
                            "Overlapping conditions with range keys are not supported in where clause");
                }
                if (reach == null || range.endsAfter(reach)) {
                    reach = range;
                }
            }
        }
    }

    List<String> unprojectedFilterAttributes(Set<String> projected) {
        if (!keyed()) {
            return List.of();
        }
        Set<String> last = Set.of();
        for (Branch branch : branches.stream().sorted(Comparator.comparing(Branch::partition)).toList()) {
            Set<String> names = branch.filters().stream()
                    .flatMap(DynamoDbPartiQLKeyPlan::attributePaths)
                    .map(Path::root)
                    .filter(root -> !projected.contains(root))
                    .collect(Collectors.toCollection(HashSet::new));
            if (names.isEmpty()) {
                return List.of();
            }
            last = names;
        }
        return List.copyOf(last);
    }

    Optional<PVal> singlePartition() {
        Key first = branches.getFirst().partition();
        if (!keyed() || branches.stream().anyMatch(branch -> branch.partition().compareTo(first) != 0)) {
            return Optional.empty();
        }
        return Optional.of(branches.getFirst().partitionValue());
    }

    static Stream<Path> attributePaths(Cond cond) {
        return DynamoDbPartiQLParser.leaves(cond).map(Cond.Leaf::path);
    }

    static boolean isSortKeyRange(Cond cond, String sortKey) {
        if (sortKey == null || cond.bareAttribute().filter(sortKey::equals).isEmpty()) {
            return false;
        }
        return cond instanceof Cond.Cmp cmp ? !"<>".equals(cmp.op())
                : cond instanceof Cond.Eq || cond instanceof Cond.Between || cond instanceof Cond.BeginsWith;
    }

    private boolean keyed() {
        return branches.stream().allMatch(branch -> branch.partition() != null);
    }

    private Branch branchOf(Spread spread) {
        Key partition = null;
        PVal partitionValue = null;
        boolean conflicting = false;
        Range sortRange = Range.ALL;
        for (Cond.Leaf condition : spread.keyConditions()) {
            if (condition.path().root().equals(partitionKey)) {
                PVal value = ((Cond.Eq) condition).val();
                Key key = Key.of(value);
                conflicting |= partition != null && partition.compareTo(key) != 0;
                if (partition == null) {
                    partition = key;
                    partitionValue = value;
                }
            } else {
                sortRange = sortRange.intersect(rangeOf(condition));
            }
        }
        return new Branch(conflicting ? null : partition, partitionValue, sortRange, spread.filters());
    }

    private List<Spread> spread(Cond cond) {
        return switch (cond) {
            case Cond.In in when isKeyCondition(in) -> {
                keyConditions.add(in);
                yield in.values().stream()
                        .map(value -> new Spread(List.of(new Cond.Eq(in.path(), value)), List.of()))
                        .toList();
            }
            case Cond.Leaf leaf when isKeyCondition(leaf) -> {
                keyConditions.add(leaf);
                yield List.of(new Spread(List.of(leaf), List.of()));
            }
            case Cond.And and -> {
                List<Spread> spread = List.of(Spread.EMPTY);
                for (Cond operand : and.operands()) {
                    spread = and(spread, spread(operand));
                }
                yield spread;
            }
            case Cond.Or or when namesKey(or) -> {
                List<Spread> spread = or.operands().stream().flatMap(operand -> spread(operand).stream()).toList();
                tooManyBranches |= spread.size() > MAX_BRANCHES;
                yield spread;
            }
            default -> List.of(new Spread(List.of(), List.of(cond)));
        };
    }

    private List<Spread> and(List<Spread> left, List<Spread> right) {
        if ((long) left.size() * right.size() > MAX_BRANCHES) {
            tooManyBranches = true;
            return left;
        }
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

    private boolean pinsPartition(Cond.Leaf leaf) {
        return leaf.bareAttribute().filter(partitionKey::equals).isPresent()
                && (leaf instanceof Cond.Eq || leaf instanceof Cond.In);
    }

    private boolean isKeyCondition(Cond.Leaf leaf) {
        if (leaf.bareAttribute().filter(partitionKey::equals).isPresent()) {
            return pinsPartition(leaf);
        }
        return isSortKeyRange(leaf, sortKey)
                || (leaf instanceof Cond.In && leaf.bareAttribute().filter(name -> name.equals(sortKey)).isPresent());
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

    private static Range rangeOf(Cond.Leaf condition) {
        return switch (condition) {
            case Cond.Eq eq -> new Range(Key.of(eq.val()), true, Key.of(eq.val()), true);
            case Cond.Between between -> new Range(Key.of(between.lo()), true, Key.of(between.hi()), true);
            case Cond.BeginsWith beginsWith -> new Range(Key.of(beginsWith.prefix()), true, prefixEnd(beginsWith.prefix()), false);
            case Cond.Cmp cmp -> switch (cmp.op()) {
                case "<" -> new Range(null, false, Key.of(cmp.val()), false);
                case "<=" -> new Range(null, false, Key.of(cmp.val()), true);
                case ">" -> new Range(Key.of(cmp.val()), false, null, false);
                default -> new Range(Key.of(cmp.val()), true, null, false);
            };
            default -> Range.ALL;
        };
    }

    private static Key prefixEnd(PVal prefix) {
        byte[] bytes = Key.of(prefix).bytes();
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] != (byte) 0xFF) {
                byte[] end = Arrays.copyOf(bytes, i + 1);
                end[i]++;
                return new Key(null, end);
            }
        }
        return null;
    }

    private record Key(BigDecimal number, byte[] bytes) implements Comparable<Key> {

        static Key of(PVal value) {
            return switch (value) {
                case PVal.Num number -> new Key(new BigDecimal(number.v()), "N".getBytes(StandardCharsets.UTF_8));
                case PVal.Str text -> new Key(null, text.v().getBytes(StandardCharsets.UTF_8));
                case PVal.Av av when av.node().has("B") -> new Key(null, Base64.getDecoder().decode(av.node().get("B").asText()));
                default -> new Key(null, DynamoDbPartiQLParser.typeCode(value).getBytes(StandardCharsets.UTF_8));
            };
        }

        @Override
        public int compareTo(Key other) {
            if (number != null && other.number != null) {
                return number.compareTo(other.number);
            }
            return Arrays.compareUnsigned(bytes, other.bytes);
        }
    }

    private record Range(Key lower, boolean lowerInclusive, Key upper, boolean upperInclusive) {

        static final Range ALL = new Range(null, false, null, false);

        static final Comparator<Range> BY_LOWER = Comparator
                .comparing(Range::lower, Comparator.nullsFirst(Comparator.<Key>naturalOrder()))
                .thenComparing(range -> !range.lowerInclusive());

        Range intersect(Range other) {
            boolean raisesLower = other.lower != null && (lower == null || other.lower.compareTo(lower) > 0
                    || (other.lower.compareTo(lower) == 0 && !other.lowerInclusive));
            boolean dropsUpper = other.upper != null && (upper == null || other.upper.compareTo(upper) < 0
                    || (other.upper.compareTo(upper) == 0 && !other.upperInclusive));
            return new Range(raisesLower ? other.lower : lower, raisesLower ? other.lowerInclusive : lowerInclusive,
                    dropsUpper ? other.upper : upper, dropsUpper ? other.upperInclusive : upperInclusive);
        }

        boolean isEmpty() {
            if (lower == null || upper == null) {
                return false;
            }
            int order = lower.compareTo(upper);
            return order > 0 || (order == 0 && !(lowerInclusive && upperInclusive));
        }

        boolean startsBefore(Range reach) {
            if (reach.upper == null || lower == null) {
                return true;
            }
            int order = lower.compareTo(reach.upper);
            return order < 0 || (order == 0 && lowerInclusive && reach.upperInclusive);
        }

        boolean endsAfter(Range reach) {
            if (reach.upper == null) {
                return false;
            }
            if (upper == null) {
                return true;
            }
            int order = upper.compareTo(reach.upper);
            return order > 0 || (order == 0 && upperInclusive && !reach.upperInclusive);
        }
    }
}
