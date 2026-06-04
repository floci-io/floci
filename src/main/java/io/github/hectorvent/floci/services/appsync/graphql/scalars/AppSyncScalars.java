package io.github.hectorvent.floci.services.appsync.graphql.scalars;

import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Pattern;

public final class AppSyncScalars {

    public static final GraphQLScalarType AWSJSON = GraphQLScalarType.newScalar()
        .name("AWSJSON")
        .description("A JSON string")
        .coercing(new Coercing<String, String>() {
            @Override
            public String serialize(Object dataFetcherResult) {
                return dataFetcherResult != null ? dataFetcherResult.toString() : null;
            }
            @Override
            public String parseValue(Object input) {
                String str = input.toString();
                try {
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(str);
                } catch (Exception e) {
                    throw new CoercingParseValueException("Invalid JSON: " + str);
                }
                return str;
            }
            @Override
            public String parseLiteral(Object input) {
                if (!(input instanceof StringValue sv)) return null;
                return parseValue(sv.getValue());
            }
        })
        .build();

    public static final GraphQLScalarType AWS_DATE_TIME = GraphQLScalarType.newScalar()
        .name("AWSDateTime")
        .description("An ISO-8601 datetime string")
        .coercing(new Coercing<String, String>() {
            @Override
            public String serialize(Object dataFetcherResult) {
                return dataFetcherResult != null ? dataFetcherResult.toString() : null;
            }
            @Override
            public String parseValue(Object input) {
                String str = input.toString();
                try {
                    Instant.parse(str);
                } catch (DateTimeParseException e) {
                    throw new CoercingParseValueException("Invalid AWSDateTime: " + str);
                }
                return str;
            }
            @Override
            public String parseLiteral(Object input) {
                if (!(input instanceof StringValue sv)) return null;
                return parseValue(sv.getValue());
            }
        })
        .build();

    public static final GraphQLScalarType AWS_DATE = GraphQLScalarType.newScalar()
        .name("AWSDate")
        .description("An ISO-8601 date string (yyyy-MM-dd)")
        .coercing(new Coercing<String, String>() {
            @Override
            public String serialize(Object dataFetcherResult) {
                return dataFetcherResult != null ? dataFetcherResult.toString() : null;
            }
            @Override
            public String parseValue(Object input) {
                String str = input.toString();
                try {
                    LocalDate.parse(str, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException e) {
                    throw new CoercingParseValueException("Invalid AWSDate: " + str);
                }
                return str;
            }
            @Override
            public String parseLiteral(Object input) {
                if (!(input instanceof StringValue sv)) return null;
                return parseValue(sv.getValue());
            }
        })
        .build();

    public static final GraphQLScalarType AWS_TIME = GraphQLScalarType.newScalar()
        .name("AWSTime")
        .description("An ISO-8601 time string (HH:mm:ss)") 
        .coercing(new Coercing<String, String>() {
            @Override
            public String serialize(Object dataFetcherResult) {
                return dataFetcherResult != null ? dataFetcherResult.toString() : null;
            }
            @Override
            public String parseValue(Object input) {
                String str = input.toString();
                try {
                    LocalTime.parse(str, DateTimeFormatter.ISO_LOCAL_TIME);
                } catch (DateTimeParseException e) {
                    throw new CoercingParseValueException("Invalid AWSTime: " + str);
                }
                return str;
            }
            @Override
            public String parseLiteral(Object input) {
                if (!(input instanceof StringValue sv)) return null;
                return parseValue(sv.getValue());
            }
        })
        .build();

    public static final GraphQLScalarType AWS_TIMESTAMP = GraphQLScalarType.newScalar()
        .name("AWSTimestamp")
        .description("Unix epoch seconds (0 to 32503680000)")
        .coercing(new Coercing<Long, Long>() {
            @Override
            public Long serialize(Object dataFetcherResult) {
                if (dataFetcherResult instanceof Number n) return n.longValue();
                return Long.parseLong(dataFetcherResult.toString());
            }
            @Override
            public Long parseValue(Object input) {
                long val = ((Number) input).longValue();
                if (val < 0 || val > 32503680000L)
                    throw new CoercingParseValueException("AWSTimestamp out of range: " + val);
                return val;
            }
            @Override
            public Long parseLiteral(Object input) {
                if (input instanceof graphql.language.IntValue iv) return parseValue(iv.getValue().longValue());
                throw new CoercingParseLiteralException("AWSTimestamp must be an integer");
            }
        })
        .build();

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    public static final GraphQLScalarType AWS_EMAIL = GraphQLScalarType.newScalar()
        .name("AWSEmail")
        .description("An RFC 5322 email address")
        .coercing(new Coercing<String, String>() {
            @Override
            public String serialize(Object dataFetcherResult) {
                return dataFetcherResult != null ? dataFetcherResult.toString() : null;
            }
            @Override
            public String parseValue(Object input) {
                String str = input.toString();
                if (!EMAIL_PATTERN.matcher(str).matches())
                    throw new CoercingParseValueException("Invalid AWSEmail: " + str);
                return str;
            }
            @Override
            public String parseLiteral(Object input) {
                if (!(input instanceof StringValue sv)) return null;
                return parseValue(sv.getValue());
            }
        })
        .build();

    public static final GraphQLScalarType AWS_URL = GraphQLScalarType.newScalar()
        .name("AWSURL")
        .description("A valid URL")
        .coercing(new Coercing<String, String>() {
            @Override
            public String serialize(Object dataFetcherResult) {
                return dataFetcherResult != null ? dataFetcherResult.toString() : null;
            }
            @Override
            public String parseValue(Object input) {
                String str = input.toString();
                try {
                    URI.create(str).toURL();
                } catch (Exception e) {
                    throw new CoercingParseValueException("Invalid AWSURL: " + str);
                }
                return str;
            }
            @Override
            public String parseLiteral(Object input) {
                if (!(input instanceof StringValue sv)) return null;
                return parseValue(sv.getValue());
            }
        })
        .build();

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+[1-9]\\d{1,14}$");

    public static final GraphQLScalarType AWS_PHONE = GraphQLScalarType.newScalar()
        .name("AWSPhone")
        .description("An E.164 phone number")
        .coercing(new Coercing<String, String>() {
            @Override
            public String serialize(Object dataFetcherResult) {
                return dataFetcherResult != null ? dataFetcherResult.toString() : null;
            }
            @Override
            public String parseValue(Object input) {
                String str = input.toString();
                if (!PHONE_PATTERN.matcher(str).matches())
                    throw new CoercingParseValueException("Invalid AWSPhone: " + str);
                return str;
            }
            @Override
            public String parseLiteral(Object input) {
                if (!(input instanceof StringValue sv)) return null;
                return parseValue(sv.getValue());
            }
        })
        .build();

    public static final GraphQLScalarType AWS_IP_ADDRESS = GraphQLScalarType.newScalar()
        .name("AWSIPAddress")
        .description("An IPv4 or IPv6 address")
        .coercing(new Coercing<String, String>() {
            @Override
            public String serialize(Object dataFetcherResult) {
                return dataFetcherResult != null ? dataFetcherResult.toString() : null;
            }
            @Override
            public String parseValue(Object input) {
                String str = input.toString();
                try {
                    InetAddress.getByName(str);
                } catch (Exception e) {
                    throw new CoercingParseValueException("Invalid AWSIPAddress: " + str);
                }
                return str;
            }
            @Override
            public String parseLiteral(Object input) {
                if (!(input instanceof StringValue sv)) return null;
                return parseValue(sv.getValue());
            }
        })
        .build();

    public static final GraphQLScalarType AWS_BOOLEAN = GraphQLScalarType.newScalar()
        .name("AWSBoolean")
        .description("A boolean value")
        .coercing(new Coercing<Boolean, Boolean>() {
            @Override
            public Boolean serialize(Object dataFetcherResult) {
                if (dataFetcherResult instanceof Boolean b) return b;
                return Boolean.valueOf(dataFetcherResult.toString());
            }
            @Override
            public Boolean parseValue(Object input) {
                if (input instanceof Boolean b) return b;
                return Boolean.valueOf(input.toString());
            }
            @Override
            public Boolean parseLiteral(Object input) {
                if (input instanceof graphql.language.BooleanValue bv) return bv.isValue();
                return null;
            }
        })
        .build();

    public static final GraphQLScalarType AWS_LONG = GraphQLScalarType.newScalar()
        .name("AWSLong")
        .description("A 64-bit signed integer")
        .coercing(new Coercing<Long, Long>() {
            @Override
            public Long serialize(Object dataFetcherResult) {
                if (dataFetcherResult instanceof Number n) return n.longValue();
                return Long.parseLong(dataFetcherResult.toString());
            }
            @Override
            public Long parseValue(Object input) {
                if (input instanceof Number n) return n.longValue();
                return Long.parseLong(input.toString());
            }
            @Override
            public Long parseLiteral(Object input) {
                if (input instanceof graphql.language.IntValue iv) return iv.getValue().longValue();
                throw new CoercingParseLiteralException("AWSLong must be an integer");
            }
        })
        .build();

    public static final GraphQLScalarType AWS_INTEGER = GraphQLScalarType.newScalar()
        .name("AWSInteger")
        .description("A 32-bit signed integer")
        .coercing(new Coercing<Integer, Integer>() {
            @Override
            public Integer serialize(Object dataFetcherResult) {
                if (dataFetcherResult instanceof Number n) return n.intValue();
                return Integer.parseInt(dataFetcherResult.toString());
            }
            @Override
            public Integer parseValue(Object input) {
                if (input instanceof Number n) return n.intValue();
                return Integer.parseInt(input.toString());
            }
            @Override
            public Integer parseLiteral(Object input) {
                if (input instanceof graphql.language.IntValue iv) return iv.getValue().intValue();
                throw new CoercingParseLiteralException("AWSInteger must be an integer");
            }
        })
        .build();

    public static final GraphQLScalarType AWS_SHORT = GraphQLScalarType.newScalar()
        .name("AWSShort")
        .description("A 16-bit signed integer (-32768 to 32767)")
        .coercing(new Coercing<Integer, Integer>() {
            @Override
            public Integer serialize(Object dataFetcherResult) {
                if (dataFetcherResult instanceof Number n) return n.intValue();
                return Integer.parseInt(dataFetcherResult.toString());
            }
            @Override
            public Integer parseValue(Object input) {
                int val;
                if (input instanceof Number n) val = n.intValue();
                else val = Integer.parseInt(input.toString());
                if (val < -32768 || val > 32767)
                    throw new CoercingParseValueException("AWSShort out of range: " + val);
                return val;
            }
            @Override
            public Integer parseLiteral(Object input) {
                if (input instanceof graphql.language.IntValue iv) return parseValue(iv.getValue().intValue());
                throw new CoercingParseLiteralException("AWSShort must be an integer");
            }
        })
        .build();

    public static final GraphQLScalarType AWS_FLOAT = GraphQLScalarType.newScalar()
        .name("AWSFloat")
        .description("An IEEE 754 double-precision float")
        .coercing(new Coercing<Double, Double>() {
            @Override
            public Double serialize(Object dataFetcherResult) {
                if (dataFetcherResult instanceof Number n) return n.doubleValue();
                return Double.parseDouble(dataFetcherResult.toString());
            }
            @Override
            public Double parseValue(Object input) {
                if (input instanceof Number n) return n.doubleValue();
                return Double.parseDouble(input.toString());
            }
            @Override
            public Double parseLiteral(Object input) {
                if (input instanceof graphql.language.FloatValue fv) return fv.getValue().doubleValue();
                if (input instanceof graphql.language.IntValue iv) return iv.getValue().doubleValue();
                throw new CoercingParseLiteralException("AWSFloat must be a number");
            }
        })
        .build();

    public static final GraphQLScalarType AWS_BIG_DECIMAL = GraphQLScalarType.newScalar()
        .name("AWSBigDecimal")
        .description("An arbitrary-precision decimal number")
        .coercing(new Coercing<String, String>() {
            @Override
            public String serialize(Object dataFetcherResult) {
                return dataFetcherResult != null ? dataFetcherResult.toString() : null;
            }
            @Override
            public String parseValue(Object input) {
                String str = input.toString();
                try {
                    new BigDecimal(str);
                } catch (NumberFormatException e) {
                    throw new CoercingParseValueException("Invalid AWSBigDecimal: " + str);
                }
                return str;
            }
            @Override
            public String parseLiteral(Object input) {
                if (input instanceof StringValue sv) return parseValue(sv.getValue());
                if (input instanceof graphql.language.FloatValue fv) return fv.getValue().toString();
                if (input instanceof graphql.language.IntValue iv) return iv.getValue().toString();
                return null;
            }
        })
        .build();

    public static final GraphQLScalarType AWS_BIG_INT = GraphQLScalarType.newScalar()
        .name("AWSBigInt")
        .description("An arbitrary-precision integer")
        .coercing(new Coercing<String, String>() {
            @Override
            public String serialize(Object dataFetcherResult) {
                return dataFetcherResult != null ? dataFetcherResult.toString() : null;
            }
            @Override
            public String parseValue(Object input) {
                String str = input.toString();
                try {
                    new BigInteger(str);
                } catch (NumberFormatException e) {
                    throw new CoercingParseValueException("Invalid AWSBigInt: " + str);
                }
                return str;
            }
            @Override
            public String parseLiteral(Object input) {
                if (input instanceof graphql.language.IntValue iv) return iv.getValue().toString();
                if (input instanceof StringValue sv) return parseValue(sv.getValue());
                return null;
            }
        })
        .build();

    public static final GraphQLScalarType AWS_BYTE = GraphQLScalarType.newScalar()
        .name("AWSByte")
        .description("A base64-encoded byte array")
        .coercing(new Coercing<String, String>() {
            @Override
            public String serialize(Object dataFetcherResult) {
                return dataFetcherResult != null ? dataFetcherResult.toString() : null;
            }
            @Override
            public String parseValue(Object input) {
                String str = input.toString();
                try {
                    Base64.getDecoder().decode(str);
                } catch (IllegalArgumentException e) {
                    throw new CoercingParseValueException("Invalid AWSByte: not valid base64");
                }
                return str;
            }
            @Override
            public String parseLiteral(Object input) {
                if (!(input instanceof StringValue sv)) return null;
                return parseValue(sv.getValue());
            }
        })
        .build();

    private AppSyncScalars() {}
}
