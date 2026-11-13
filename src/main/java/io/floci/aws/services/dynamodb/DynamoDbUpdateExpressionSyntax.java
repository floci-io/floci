package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.ExpressionEvaluator.Token;
import io.floci.aws.services.dynamodb.ExpressionEvaluator.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Refuses an UpdateExpression that DynamoDB cannot parse, with the token and the text it reports.
 * Given the request's names and values, it also refuses what DynamoDB finds wrong in the parsed
 * expression before it reads the table.
 */
final class DynamoDbUpdateExpressionSyntax {

    private static final String EXPRESSION_TYPE = "UpdateExpression";

    private static final Set<String> CONDITION_FUNCTIONS = Set.of(
            "attribute_exists", "attribute_not_exists", "attribute_type", "begins_with", "contains", "size");

    private static final Map<String, String> ADD_OR_DELETE_OPERAND_TYPE_NAMES = Map.of(
            "S", "STRING", "N", "NUMBER", "B", "Binary", "BOOL", "BOOL", "NULL", "NULL", "L", "LIST", "M", "MAP");

    private sealed interface Operand permits PathOperand, ValueOperand, Call, Sum, Group {}

    private record PathOperand(List<Token> names) implements Operand {}

    private record ValueOperand(Token placeholder) implements Operand {}

    private record Call(Token function, List<Operand> arguments) implements Operand {}

    private record Sum(Operand left, Token sign, Operand right) implements Operand {}

    private record Group(Operand inner) implements Operand {}

    private record Action(String section, PathOperand target, Operand value) {}

    private final String expression;
    private final List<Token> tokens;
    private final List<Action> actions = new ArrayList<>();
    private final JsonNode names;
    private final JsonNode values;
    private int pos;

    private DynamoDbUpdateExpressionSyntax(String expression, JsonNode names, JsonNode values) {
        this.expression = expression;
        this.tokens = ExpressionEvaluator.tokenize(expression);
        this.names = names;
        this.values = values;
    }

    static void check(String expression) {
        new DynamoDbUpdateExpressionSyntax(expression, null, null).readSections();
    }

    static void check(String expression, JsonNode names, JsonNode values) {
        DynamoDbUpdateExpressionSyntax syntax = new DynamoDbUpdateExpressionSyntax(expression, names, values);
        syntax.readSections();
        syntax.checkActions();
    }

    private void readSections() {
        do {
            if (!isSection(peek())) {
                throw syntaxError();
            }
            String section = tokens.get(pos++).value().toUpperCase(Locale.ROOT);
            do {
                actions.add(readAction(section));
            } while (accept(TokenType.COMMA));
        } while (peek().type() != TokenType.EOF);
    }

    private Action readAction(String section) {
        PathOperand target = readPath();
        Operand value = switch (section) {
            case "SET" -> {
                expect(TokenType.EQ);
                yield readValue();
            }
            case "ADD", "DELETE" -> {
                Token placeholder = peek();
                expect(TokenType.VALUE_REF);
                yield new ValueOperand(placeholder);
            }
            default -> null;
        };
        return new Action(section, target, value);
    }

    // DynamoDB allows a sum (a + b or a - b) only as the whole value, alone or in parentheses.
    private Operand readValue() {
        Operand first = readOperand(true);
        if (!isSign(peek())) {
            return first;
        }
        if (first instanceof Group group && group.inner() instanceof Sum) {
            throw syntaxError();
        }
        Token sign = tokens.get(pos++);
        return new Sum(first, sign, readOperand(false));
    }

    // DynamoDB refuses redundant parentheses as soon as it closes them, before any later syntax error.
    private Operand readOperand(boolean sumAllowed) {
        Token token = peek();
        if (token.type() == TokenType.LPAREN) {
            pos++;
            Operand inner = sumAllowed ? readValue() : readOperand(false);
            expect(TokenType.RPAREN);
            if (inner instanceof Group) {
                throw new AwsException("ValidationException",
                        "Invalid UpdateExpression: The expression has redundant parentheses;", 400);
            }
            return new Group(inner);
        }
        if (token.type() == TokenType.VALUE_REF) {
            pos++;
            return new ValueOperand(token);
        }
        if (token.type() != TokenType.NAME_REF && isName(token)
                && tokens.get(pos + 1).type() == TokenType.LPAREN) {
            pos += 2;
            List<Operand> arguments = new ArrayList<>();
            do {
                arguments.add(readOperand(false));
            } while (accept(TokenType.COMMA));
            expect(TokenType.RPAREN);
            return new Call(token, arguments);
        }
        return readPath();
    }

    private PathOperand readPath() {
        List<Token> pathNames = new ArrayList<>();
        pathNames.add(expectName());
        while (true) {
            if (accept(TokenType.DOT)) {
                pathNames.add(expectName());
            } else if (accept(TokenType.LBRACKET)) {
                expect(TokenType.NUMBER);
                expect(TokenType.RBRACKET);
            } else {
                return new PathOperand(pathNames);
            }
        }
    }

    private Token expectName() {
        Token token = peek();
        if (!isName(token)) {
            throw syntaxError();
        }
        pos++;
        return token;
    }

    // DynamoDB checks every placeholder, name and call in written order, the operands of a call
    // before the call itself.
    private void checkActions() {
        for (Action action : actions) {
            checkPath(action.target());
            switch (action.section()) {
                case "SET" -> checkOperand(action.value(), false);
                case "ADD", "DELETE" -> checkAddOrDeleteOperand(action.section(), action.value());
                default -> { }
            }
        }
    }

    private void checkOperand(Operand operand, boolean nested) {
        switch (operand) {
            case PathOperand path -> checkPath(path);
            case ValueOperand value -> {
                String placeholder = value.placeholder().value();
                if (values == null || !values.has(placeholder)) {
                    throw ExpressionEvaluator.undefinedValue(EXPRESSION_TYPE, placeholder);
                }
            }
            case Group group -> checkOperand(group.inner(), nested);
            case Sum sum -> {
                checkOperand(sum.left(), true);
                checkOperand(sum.right(), true);
                requireOperandTypes(sum.sign().value(), "N", sum.left(), sum.right());
            }
            case Call call -> {
                for (Operand argument : call.arguments()) {
                    checkOperand(argument, true);
                }
                checkCall(call, nested);
            }
        }
    }

    private void checkPath(PathOperand path) {
        for (Token name : path.names()) {
            if (name.type() == TokenType.NAME_REF) {
                if (names == null || !names.has(name.value())) {
                    throw ExpressionEvaluator.undefinedName(EXPRESSION_TYPE, name.value());
                }
            } else if (DynamoDbReservedWords.isReserved(name.value())) {
                throw DynamoDbReservedWords.reservedWord(EXPRESSION_TYPE, name.value());
            }
        }
    }

    // DynamoDB allows list_append only as the whole value, alone or in parentheses.
    private void checkCall(Call call, boolean nested) {
        String function = call.function().value();
        if (CONDITION_FUNCTIONS.contains(function)) {
            throw invalid("The function is not allowed in an update expression; function: " + function);
        }
        if (!"if_not_exists".equals(function) && !"list_append".equals(function)) {
            throw invalid("Invalid function name; function: " + function);
        }
        List<Operand> arguments = call.arguments();
        if (arguments.size() != 2) {
            throw invalid("Incorrect number of operands for operator or function; operator or function: "
                    + function + ", number of operands: " + arguments.size());
        }
        if ("if_not_exists".equals(function)) {
            if (!isPath(arguments.getFirst())) {
                throw invalid("Operator or function requires a document path; operator or function: "
                        + function);
            }
            return;
        }
        if (nested) {
            throw invalid("The function is not allowed to be used this way in an expression; function: "
                    + function);
        }
        requireOperandTypes(function, "L", arguments.getFirst(), arguments.getLast());
    }

    private void checkAddOrDeleteOperand(String section, Operand value) {
        checkOperand(value, false);
        String type = staticType(value);
        Set<String> allowed = "ADD".equals(section) ? Set.of("N", "SS", "NS", "BS") : Set.of("SS", "NS", "BS");
        if (!allowed.contains(type)) {
            throw invalid("Incorrect operand type for operator or function; operator: " + section
                    + ", operand type: " + ADD_OR_DELETE_OPERAND_TYPE_NAMES.get(type)
                    + ", typeSet: ALLOWED_FOR_" + section + "_OPERAND");
        }
    }

    private void requireOperandTypes(String operator, String expected, Operand first, Operand second) {
        for (Operand operand : List.of(first, second)) {
            String type = staticType(operand);
            if (type != null && !type.equals(expected)) {
                throw invalid("Incorrect operand type for operator or function; operator or function: "
                        + operator + ", operand type: " + type);
            }
        }
    }

    // A path or a call has no type until DynamoDB reads the item.
    private String staticType(Operand operand) {
        return switch (operand) {
            case ValueOperand value -> DynamoDbAttributeValueValidator.typeOf(values.get(value.placeholder().value()));
            case Group group -> staticType(group.inner());
            default -> null;
        };
    }

    private static boolean isPath(Operand operand) {
        return switch (operand) {
            case PathOperand path -> true;
            case Group group -> isPath(group.inner());
            default -> false;
        };
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", "Invalid UpdateExpression: " + message, 400);
    }

    // Function names are not keywords, so size or if_not_exists can name an attribute.
    private static boolean isName(Token token) {
        return switch (token.type()) {
            case NAME_REF, FUNCTION -> true;
            case IDENTIFIER -> !isSection(token);
            default -> false;
        };
    }

    private static boolean isSection(Token token) {
        return token.type() == TokenType.IDENTIFIER
                && DynamoDbService.UPDATE_SECTIONS.contains(token.value().toUpperCase(Locale.ROOT));
    }

    private static boolean isSign(Token token) {
        return token.type() == TokenType.UNKNOWN && ("+".equals(token.value()) || "-".equals(token.value()));
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private boolean accept(TokenType type) {
        if (peek().type() != type) {
            return false;
        }
        pos++;
        return true;
    }

    private void expect(TokenType type) {
        if (!accept(type)) {
            throw syntaxError();
        }
    }

    private AwsException syntaxError() {
        return new AwsException("ValidationException", "Invalid UpdateExpression: Syntax error; "
                + ExpressionEvaluator.syntaxErrorDetail(expression, tokens, pos), 400);
    }
}
