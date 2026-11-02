package io.github.hectorvent.floci.services.redshift.proxy;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The format a COPY {@code DATEFORMAT} or {@code TIMEFORMAT} names, and the conversion of a source value
 * into the ISO text PostgreSQL reads. A format is a string of dateparts and timeparts with separators between
 * them, {@code 'auto'}, or for TIMEFORMAT an epoch keyword. {@code 'auto'} is left to PostgreSQL, whose own
 * parser already reads most of the forms Redshift recognizes, so it converts nothing.
 */
final class CopyTemporalFormat {

    private enum Mode {
        PATTERN, ISO, AUTO, EPOCH_SECONDS, EPOCH_MILLISECONDS
    }

    /** Tokens in the order they are tried, so a longer one is never cut short by a prefix of it. */
    private static final List<String> TOKENS = List.of(
            "YYYY", "HH24", "HH12", "MON", "YY", "MM", "DD", "HH", "MI", "SS", "AM", "PM");
    private static final Pattern EPOCH = Pattern.compile("-?\\d+");
    private static final Pattern ISO = Pattern.compile(
            "^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,6}))?)?)?$");
    private static final int TWO_DIGIT_YEAR_PIVOT = 70;
    private static final int MAX_YEAR = 9999;

    private final String spec;
    private final Mode mode;
    private final Pattern pattern;
    private final List<String> groups;

    private CopyTemporalFormat(String spec, Mode mode, Pattern pattern, List<String> groups) {
        this.spec = spec;
        this.mode = mode;
        this.pattern = pattern;
        this.groups = groups;
    }

    /**
     * What a field is read with when ACCEPTANYDATE names no format: the ISO form of a date, optionally followed
     * by a time of day, which is what the default formats and PostgreSQL both read. A value in any other shape
     * is the one ACCEPTANYDATE turns into NULL.
     */
    static CopyTemporalFormat defaultIso() {
        return new CopyTemporalFormat("YYYY-MM-DD[ HH:MI:SS]", Mode.ISO, ISO, List.of());
    }

    /**
     * Reads the quoted argument of DATEFORMAT or TIMEFORMAT; null when it is not a format Redshift accepts.
     * The keywords {@code auto}, {@code epochsecs} and {@code epochmillisecs} are case-sensitive.
     *
     * @param time true for TIMEFORMAT, which also accepts the epoch keywords and refuses a time zone part
     */
    static CopyTemporalFormat parse(String spec, boolean time) {
        if (spec == null || spec.isEmpty()) {
            return null;
        }
        if ("auto".equals(spec)) {
            return new CopyTemporalFormat(spec, Mode.AUTO, null, List.of());
        }
        if (time && "epochsecs".equals(spec)) {
            return new CopyTemporalFormat(spec, Mode.EPOCH_SECONDS, null, List.of());
        }
        if (time && "epochmillisecs".equals(spec)) {
            return new CopyTemporalFormat(spec, Mode.EPOCH_MILLISECONDS, null, List.of());
        }
        return compile(spec);
    }

    /** One element of a format string: a token, or a separator character. */
    private record Part(String token, char separator) {
        boolean isToken() {
            return token != null;
        }

        boolean isNumeric() {
            return token != null && !"MON".equals(token) && !"AM".equals(token) && !"PM".equals(token);
        }
    }

    private static CopyTemporalFormat compile(String spec) {
        List<Part> parts = new ArrayList<>();
        int position = 0;
        while (position < spec.length()) {
            String token = tokenAt(spec, position);
            if (token != null) {
                parts.add(new Part(token, '\0'));
                position += token.length();
                continue;
            }
            char c = spec.charAt(position);
            if (Character.isLetterOrDigit(c)) {
                return null;
            }
            parts.add(new Part(null, c));
            position++;
        }
        if (!partsAreConsistent(parts)) {
            return null;
        }
        StringBuilder regex = new StringBuilder("^");
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            if (!part.isToken()) {
                regex.append(Pattern.quote(String.valueOf(part.separator())));
                continue;
            }
            // Numeric tokens with nothing between them cannot be told apart by a variable width.
            boolean adjacent = part.isNumeric() && ((i > 0 && parts.get(i - 1).isNumeric())
                    || (i + 1 < parts.size() && parts.get(i + 1).isNumeric()));
            regex.append(regexFor(part.token(), adjacent));
            groups.add(part.token());
            if ("SS".equals(part.token())) {
                groups.add("FRACTION");
            }
        }
        return new CopyTemporalFormat(spec, Mode.PATTERN, Pattern.compile(regex.append('$').toString()), groups);
    }

    /**
     * A part of a date or time may be named once, a 12-hour clock needs its meridian, and a meridian needs an
     * hour: anything else is a format whose value would be read ambiguously.
     */
    private static boolean partsAreConsistent(List<Part> parts) {
        Set<String> seen = new HashSet<>();
        boolean twelveHour = false;
        for (Part part : parts) {
            if (!part.isToken()) {
                continue;
            }
            if (!seen.add(category(part.token()))) {
                return false;
            }
            twelveHour |= "HH12".equals(part.token());
        }
        boolean meridian = seen.contains("meridian");
        return !seen.isEmpty() && (!twelveHour || meridian) && (!meridian || seen.contains("hour"));
    }

    private static String category(String token) {
        return switch (token) {
            case "YYYY", "YY" -> "year";
            case "MM", "MON" -> "month";
            case "DD" -> "day";
            case "HH", "HH24", "HH12" -> "hour";
            case "MI" -> "minute";
            case "SS" -> "second";
            default -> "meridian";
        };
    }

    private static String tokenAt(String spec, int position) {
        for (String token : TOKENS) {
            if (spec.regionMatches(true, position, token, 0, token.length())) {
                return token;
            }
        }
        return null;
    }

    private static String regexFor(String token, boolean exactWidth) {
        String twoDigits = exactWidth ? "(\\d{2})" : "(\\d{1,2})";
        return switch (token) {
            case "YYYY" -> "(\\d{4})";
            case "YY" -> "(\\d{2})";
            case "MON" -> "([A-Za-z]{3,9})";
            case "SS" -> twoDigits + "(?:\\.(\\d{1,6}))?";
            case "AM", "PM" -> "([AaPp][Mm])";
            default -> twoDigits;
        };
    }

    String spec() {
        return spec;
    }

    /** False for {@code 'auto'}, which leaves every value to PostgreSQL. */
    boolean converts() {
        return mode != Mode.AUTO;
    }

    /** Text PostgreSQL reads as a date: the value converted to {@code YYYY-MM-DD}, any time of day dropped. */
    String toDate(String value) {
        if (mode == Mode.AUTO) {
            return value;
        }
        return parseValue(value).toLocalDate().toString();
    }

    /** Text PostgreSQL reads as a timestamp; a format without a time of day gives midnight. */
    String toTimestamp(String value) {
        if (mode == Mode.AUTO) {
            return value;
        }
        LocalDateTime parsed = parseValue(value);
        String text = parsed.toLocalDate() + " " + parsed.toLocalTime();
        return parsed.getSecond() == 0 && parsed.getNano() == 0 ? text + ":00" : text;
    }

    private LocalDateTime parseValue(String value) {
        String text = value.trim();
        return switch (mode) {
            case EPOCH_SECONDS -> epoch(text, false);
            case EPOCH_MILLISECONDS -> epoch(text, true);
            case ISO -> parseIso(text);
            default -> parsePattern(text);
        };
    }

    private LocalDateTime epoch(String text, boolean milliseconds) {
        if (!EPOCH.matcher(text).matches()) {
            throw invalid(text);
        }
        try {
            long amount = Long.parseLong(text);
            Instant instant = milliseconds ? Instant.ofEpochMilli(amount) : Instant.ofEpochSecond(amount);
            LocalDateTime parsed = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            if (parsed.getYear() < 1 || parsed.getYear() > MAX_YEAR) {
                throw invalid(text);
            }
            return parsed;
        } catch (NumberFormatException | DateTimeException e) {
            throw invalid(text);
        }
    }

    private LocalDateTime parseIso(String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.matches()) {
            throw invalid(text);
        }
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
            int hour = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
            int minute = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
            int second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
            int nanos = matcher.group(7) == null ? 0 : fractionToNanos(matcher.group(7));
            return LocalDateTime.of(date, LocalTime.of(hour, minute, second, nanos));
        } catch (DateTimeException e) {
            throw invalid(text);
        }
    }

    private LocalDateTime parsePattern(String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.matches()) {
            throw invalid(text);
        }
        int year = 1970;
        int month = 1;
        int day = 1;
        int hour = 0;
        int minute = 0;
        int second = 0;
        int nanos = 0;
        boolean twelveHour = false;
        String meridian = null;
        int group = 1;
        try {
            for (String token : groups) {
                String captured = matcher.group(group++);
                switch (token) {
                    case "YYYY" -> year = Integer.parseInt(captured);
                    case "YY" -> {
                        int shortYear = Integer.parseInt(captured);
                        year = shortYear < TWO_DIGIT_YEAR_PIVOT ? 2000 + shortYear : 1900 + shortYear;
                    }
                    case "MM" -> month = Integer.parseInt(captured);
                    case "MON" -> month = monthNumber(captured, text);
                    case "DD" -> day = Integer.parseInt(captured);
                    case "HH", "HH24" -> hour = Integer.parseInt(captured);
                    case "HH12" -> {
                        hour = Integer.parseInt(captured);
                        twelveHour = true;
                    }
                    case "MI" -> minute = Integer.parseInt(captured);
                    case "SS" -> second = Integer.parseInt(captured);
                    case "FRACTION" -> nanos = captured == null ? 0 : fractionToNanos(captured);
                    case "AM", "PM" -> meridian = captured.toUpperCase(Locale.ROOT);
                    default -> throw invalid(text);
                }
            }
            hour = applyMeridian(hour, twelveHour, meridian, text);
            return LocalDateTime.of(LocalDate.of(year, month, day), LocalTime.of(hour, minute, second, nanos));
        } catch (DateTimeException e) {
            throw invalid(text);
        }
    }

    private int applyMeridian(int hour, boolean twelveHour, String meridian, String text) {
        if (meridian == null && !twelveHour) {
            return hour;
        }
        if (hour < 1 || hour > 12) {
            throw invalid(text);
        }
        boolean pm = "PM".equals(meridian);
        return (hour % 12) + (pm ? 12 : 0);
    }

    private int monthNumber(String name, String text) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (Month month : Month.values()) {
            String full = month.name().toLowerCase(Locale.ROOT);
            if (full.equals(lower) || (lower.length() == 3 && full.startsWith(lower))) {
                return month.getValue();
            }
        }
        throw invalid(text);
    }

    private static int fractionToNanos(String digits) {
        String padded = (digits + "000000000").substring(0, 9);
        return Integer.parseInt(padded);
    }

    private IllegalArgumentException invalid(String text) {
        return new IllegalArgumentException("'" + text + "' does not match the format '" + spec + "'");
    }
}
