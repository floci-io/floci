package io.floci.aws.services.dynamodb;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.ExpressionEvaluator.Token;
import io.floci.aws.services.dynamodb.ExpressionEvaluator.TokenType;

import java.util.List;
import java.util.Locale;

/**
 * Refuses an UpdateExpression that DynamoDB cannot parse, with the token and the text it reports.
 */
final class DynamoDbUpdateExpressionSyntax {

    // DynamoDB allows a sum (a + b or a - b) only as the whole value, alone or in parentheses.
    private enum Shape { OPERAND, GROUP, SUM, SUM_GROUP }

    private final String expression;
    private final List<Token> tokens;
    private int pos;

    private DynamoDbUpdateExpressionSyntax(String expression) {
        this.expression = expression;
        this.tokens = ExpressionEvaluator.tokenize(expression);
    }

    static void check(String expression) {
        new DynamoDbUpdateExpressionSyntax(expression).readSections();
    }

    private void readSections() {
        do {
            if (!isSection(peek())) {
                throw syntaxError();
            }
            String section = tokens.get(pos++).value().toUpperCase(Locale.ROOT);
            do {
                readAction(section);
            } while (accept(TokenType.COMMA));
        } while (peek().type() != TokenType.EOF);
    }

    private void readAction(String section) {
        readPath();
        switch (section) {
            case "SET" -> {
                expect(TokenType.EQ);
                readValue();
            }
            case "ADD", "DELETE" -> expect(TokenType.VALUE_REF);
            default -> { }
        }
    }

    private Shape readValue() {
        Shape first = readOperand(true);
        if (!isSign(peek())) {
            return first;
        }
        if (first == Shape.SUM_GROUP) {
            throw syntaxError();
        }
        pos++;
        readOperand(false);
        return Shape.SUM;
    }

    // DynamoDB refuses redundant parentheses as soon as it closes them, before any later syntax error.
    private Shape readOperand(boolean sumAllowed) {
        Token token = peek();
        if (token.type() == TokenType.LPAREN) {
            pos++;
            Shape inner = sumAllowed ? readValue() : readOperand(false);
            expect(TokenType.RPAREN);
            if (inner == Shape.GROUP || inner == Shape.SUM_GROUP) {
                throw new AwsException("ValidationException",
                        "Invalid UpdateExpression: The expression has redundant parentheses;", 400);
            }
            return inner == Shape.SUM ? Shape.SUM_GROUP : Shape.GROUP;
        }
        if (token.type() == TokenType.VALUE_REF) {
            pos++;
            return Shape.OPERAND;
        }
        if (token.type() != TokenType.NAME_REF && isName(token)
                && tokens.get(pos + 1).type() == TokenType.LPAREN) {
            pos += 2;
            do {
                readOperand(false);
            } while (accept(TokenType.COMMA));
            expect(TokenType.RPAREN);
            return Shape.OPERAND;
        }
        readPath();
        return Shape.OPERAND;
    }

    private void readPath() {
        expectName();
        while (true) {
            if (accept(TokenType.DOT)) {
                expectName();
            } else if (accept(TokenType.LBRACKET)) {
                expect(TokenType.NUMBER);
                expect(TokenType.RBRACKET);
            } else {
                return;
            }
        }
    }

    private void expectName() {
        if (!isName(peek())) {
            throw syntaxError();
        }
        pos++;
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
