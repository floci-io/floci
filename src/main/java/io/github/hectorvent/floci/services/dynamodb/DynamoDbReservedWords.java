package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates that DynamoDB expressions do not use reserved words as bare attribute names.
 */
final class DynamoDbReservedWords {

    private DynamoDbReservedWords() {}

    // Expression clause/operator keywords that are syntactically valid as operators
    // and should not be treated as attribute name tokens.
    private static final Set<String> CLAUSE_KEYWORDS = Set.of(
        "SET", "REMOVE", "ADD", "DELETE",
        "AND", "OR", "NOT", "BETWEEN", "IN",
        "TRUE", "FALSE"
    );

    // Full DynamoDB reserved words list (uppercase)
    private static final Set<String> RESERVED = Set.of(
        "ABORT", "ABSOLUTE", "ACTION", "ADD", "AFTER", "AGENT", "ALL", "ALLOCATE",
        "ALTER", "ANALYZE", "AND", "ANY", "ARCHIVE", "ARE", "ARRAY", "AS", "ASC",
        "ASCII", "ASENSITIVE", "ASSERTION", "ASYMMETRIC", "AT", "ATOMIC", "ATTACH",
        "ATTRIBUTE", "AUTH", "AUTHORIZATION", "AUTHORIZE", "AUTO",
        "BETWEEN", "BIGINT", "BINARY", "BIT", "BLOB", "BLOCK", "BOOLEAN", "BOTH",
        "BREADTH", "BUCKET", "BULK", "BY",
        "CALL", "CALLED", "CANCEL", "CASCADE", "CASE", "CAST", "CATALOG", "CHAR",
        "CHARACTER", "CHECK", "CLASS", "CLOB", "CLOSE", "CLUSTER", "CLUSTERED",
        "CLUSTERING", "CLUSTERS", "COALESCE", "COLLATE", "COLLATION", "COLUMN",
        "COLUMNS", "COMBINE", "COMMENT", "COMMIT", "COMPACT", "COMPILE", "COMPRESS",
        "CONDITION", "CONFLICT", "CONNECT", "CONNECTION", "CONSISTENCY", "CONSISTENT",
        "CONSTRAINT", "CONSTRAINTS", "CONSTRUCTOR", "CONSUMED", "CONTINUE", "CONVERT",
        "COPY", "CORRESPONDING", "COUNT", "COUNTER", "CREATE", "CROSS", "CUBE",
        "CURRENT", "CURSOR", "CYCLE",
        "DATA", "DATABASE", "DATE", "DATETIME", "DAY", "DEALLOCATE", "DEC", "DECIMAL",
        "DECLARE", "DEFAULT", "DEFERRABLE", "DEFERRED", "DEFINE", "DEFINED",
        "DEFINITION", "DELETE", "DELIMITED", "DEPTH", "DEREF", "DESC", "DESCRIBE",
        "DESCRIPTOR", "DETACH", "DETERMINISTIC", "DIAGNOSTICS", "DIRECTORIES",
        "DISABLE", "DISCONNECT", "DISTINCT", "DISTRIBUTE", "DO", "DOMAIN", "DOUBLE",
        "DROP", "DUMP", "DURATION", "DYNAMIC",
        "EACH", "ELEMENT", "ELSE", "ELSEIF", "EMPTY", "ENABLE", "END", "EQUALS",
        "ERROR", "ESCAPE", "EVALUATED", "EXCEPT", "EXCEPTION", "EXCEPTIONS",
        "EXCLUSIVE", "EXEC", "EXECUTE", "EXISTS", "EXIT", "EXPLAIN", "EXPLODE",
        "EXPORT", "EXTENDED", "EXTERNAL",
        "FALSE", "FETCH", "FILTER", "FIRST", "FIXED", "FLATTERN", "FLOAT", "FOR",
        "FORCE", "FOREIGN", "FORMAT", "FORWARD", "FOUND", "FREE", "FROM", "FULL",
        "FUNCTION", "FUNCTIONS",
        "GENERAL", "GENERATE", "GET", "GLOB", "GO", "GOTO", "GRANT", "GREATER",
        "GROUP", "GROUPING",
        "HANDLER", "HASH", "HAVE", "HAVING", "HEAP", "HIDDEN", "HOLD", "HOW",
        "IDENTIFIED", "IDENTITY", "IF", "IGNORE", "IMMEDIATE", "IN", "INCLUDING",
        "INCLUSIVE", "INCREMENT", "INCREMENTAL", "INDEX", "INDEXES", "INDICATOR",
        "INFINITE", "INITIALLY", "INLINE", "INNER", "INNTER", "INOUT", "INSERT",
        "INSTEAD", "INT", "INTEGER", "INTERSECT", "INTERVAL", "INTO", "INVALIDATE",
        "IS", "ISOLATION", "ITEM", "ITEMS", "ITERATE",
        "JOIN",
        "KEY", "KEYS",
        "LAG", "LANGUAGE", "LARGE", "LAST", "LATERAL", "LEAD", "LEADING", "LEAVE",
        "LEFT", "LENGTH", "LESS", "LEVEL", "LIKE", "LIMIT", "LIMITED", "LINES",
        "LIST", "LOAD", "LOCAL", "LOCALTIME", "LOCALTIMESTAMP", "LOCATION", "LOCATOR",
        "LOCK", "LOCKS", "LOG", "LOGED", "LONG", "LOOP", "LOWER",
        "MAP", "MATCH", "MATERIALIZED", "MAX", "MAXLEN", "MERGE", "METADATA", "MIN",
        "MINUS", "MODIFIES", "MODIFY", "MODULE", "MONTH", "MULTI", "MULTISET",
        "NAME", "NAMES", "NATIONAL", "NATURAL", "NCHAR", "NCLOB", "NEW", "NEXT",
        "NO", "NONE", "NOT", "NULL", "NULLIF", "NUMBER", "NUMERIC",
        "OBJECT", "OF", "OFFLINE", "OFFSET", "OLD", "ON", "ONLINE", "ONLY",
        "OPAQUE", "OPEN", "OPERATOR", "OPTION", "OR", "ORDER", "ORDINALITY",
        "OTHER", "OTHERS", "OUT", "OUTER", "OVER", "OVERLAPS", "OVERRIDE", "OWNER",
        "PAD", "PARALLEL", "PARAMETER", "PARAMETERS", "PARTIAL", "PARTITION",
        "PASSWORD", "PATH", "PERCENT", "PERCENTILE", "PERMISSION", "PERMISSIONS",
        "PIPE", "PIPELINED", "PLAN", "POOL", "POSITION", "PRECISION", "PREPARE",
        "PRIMARY", "PRIOR", "PRIVILEGES", "PROCEDURE", "PROCESSED", "PROJECT",
        "PROJECTION", "PROJECTIONS", "PROTO", "PULL", "PUT",
        "QUERY", "QUIT", "QUORUM",
        "RAISE", "RANDOM", "RANGE", "RANK", "RAW", "READ", "READS", "REAL",
        "REBUILD", "RECORD", "RECURSIVE", "REDUCE", "REF", "REFERENCE", "REFERENCES",
        "REFERENCING", "REGEXP", "REGION", "REINDEX", "RELATIVE", "RELEASE",
        "REMAINDER", "RENAME", "REPEAT", "REPLACE", "REQUEST", "RESET", "RESIGNAL",
        "RESOURCE", "RESPONSE", "RESTORE", "RESTRICT", "RESULT", "RETURN",
        "RETURNING", "RETURNS", "REVERSE", "REVOKE", "RIGHT", "ROLE", "ROLES",
        "ROLLBACK", "ROLLUP", "ROUTINE", "ROW", "ROWS", "RULE",
        "SAVEPOINT", "SCAN", "SCHEMA", "SCOPE", "SCROLL", "SEARCH", "SECOND",
        "SECTION", "SEGMENT", "SEGMENTS", "SELECT", "SELF", "SEMI", "SENSITIVE",
        "SEQUENCE", "SERIALIZABLE", "SESSION", "SET", "SETS", "SIGNAL", "SIMILAR",
        "SIZE", "SKEWED", "SMALLINT", "SNAPSHOT", "SOME", "SOURCE", "SPACE",
        "SPACES", "SPARSE", "SPECIFIC", "SPECIFICTYPE", "SPLIT", "SQL", "SQLCODE",
        "SQLERROR", "SQLEXCEPTION", "SQLSTATE", "SQLWARNING", "START", "STATE",
        "STATIC", "STATUS", "STORAGE", "STORE", "STORED", "STREAM", "STRING",
        "STRUCT", "STYLE", "SUB", "SUBMULTISET", "SUBPAGES", "SUBSTRING", "SUM",
        "SYMMETRIC", "SYSTEM",
        "TABLE", "TABLESAMPLE", "TEMP", "TEMPORARY", "TERMINATED", "TEXT", "THAN",
        "THEN", "THROUGHPUT", "TINYINT", "TO", "TOKEN", "TOTAL", "TOUCH",
        "TRAILING", "TRANSACTION", "TRANSFORM", "TRANSLATE", "TRANSLATION", "TREAT",
        "TRIGGER", "TRIM", "TRUE", "TRUNCATE", "TTL", "TUPLE", "TYPE",
        "UNDER", "UNDO", "UNION", "UNIQUE", "UNIT", "UNKNOWN", "UNLOGGED",
        "UNNEST", "UNPROCESSED", "UNSIGNED", "UNTIL", "UPDATE", "UPPER", "URL",
        "USAGE", "USE", "USER", "UUID",
        "VACUUM", "VALUE", "VALUED", "VALUES", "VARCHAR", "VARIABLE", "VARIANCE",
        "VARYING", "VIEW", "VIEWS", "VIRTUAL", "VOID",
        "WAIT", "WHEN", "WHERE", "WHILE", "WINDOW", "WITH", "WITHIN", "WITHOUT",
        "WRITE",
        "YEAR",
        "ZONE"
    );

    /**
     * Validates that the expression does not use any reserved words as bare attribute names.
     * Throws ValidationException if a reserved word is found without a # alias.
     */
    static void check(String expression, String expressionType) {
        if (expression == null || expression.isBlank()) return;
        List<String> bareIdents = extractBareIdentifiers(expression);
        for (String ident : bareIdents) {
            String upper = ident.toUpperCase();
            if (CLAUSE_KEYWORDS.contains(upper)) continue;
            if (RESERVED.contains(upper)) {
                throw new AwsException("ValidationException",
                        "Invalid " + expressionType + ": Attribute name is a reserved keyword; "
                        + "reserved word: " + ident, 400);
            }
        }
    }

    private static List<String> extractBareIdentifiers(String expr) {
        List<String> result = new ArrayList<>();
        int i = 0;
        int len = expr.length();
        while (i < len) {
            char c = expr.charAt(i);
            if (c == '#' || c == ':') {
                // Skip alias or value ref
                i++;
                while (i < len && isIdentChar(expr.charAt(i))) i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < len && isIdentChar(expr.charAt(i))) i++;
                String word = expr.substring(start, i);
                // Skip whitespace to check if followed by '('
                int j = i;
                while (j < len && expr.charAt(j) == ' ') j++;
                if (j < len && expr.charAt(j) == '(') {
                    // Function call — skip
                } else {
                    result.add(word);
                }
            } else {
                i++;
            }
        }
        return result;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
