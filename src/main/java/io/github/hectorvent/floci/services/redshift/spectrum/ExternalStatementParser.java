package io.github.hectorvent.floci.services.redshift.spectrum;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ExternalStatementParser {
    private static final Pattern CREATE_SCHEMA = Pattern.compile("(?is)^\\s*CREATE\\s+EXTERNAL\\s+SCHEMA\\s+(.+?)\\s+FROM\\s+DATA\\s+CATALOG\\s+DATABASE\\s+'((?:''|[^'])*)'(?:\\s+REGION\\s+'(?:''|[^'])*')?\\s+IAM_ROLE\\s+(.+?)(?:\\s+CREATE\\s+EXTERNAL\\s+DATABASE\\s+IF\\s+NOT\\s+EXISTS)?\\s*;?\\s*$");
    private static final Pattern CREATE_TABLE_HEAD = Pattern.compile("(?is)^\\s*CREATE\\s+EXTERNAL\\s+TABLE\\s+(.+?)\\.(.+?)\\s*\\(");
    private static final Pattern DROP_TABLE = Pattern.compile("(?is)^\\s*DROP\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?([^.;\\s]+)\\.([^.;\\s]+)(?:\\s+(?:CASCADE|RESTRICT))?\\s*;?\\s*$");
    private static final Pattern DROP_SCHEMA = Pattern.compile("(?is)^\\s*DROP\\s+SCHEMA\\s+(IF\\s+EXISTS\\s+)?([^\\s;]+)(?:\\s+(?:CASCADE|RESTRICT))?\\s*;?\\s*$");
    private static final Pattern ADD_PARTITIONS = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+([^.;\\s]+)\\.([^.;\\s]+)\\s+ADD\\s+(IF\\s+NOT\\s+EXISTS\\s+)?(.+?)\\s*;?\\s*$");

    public Optional<ExternalStatement> parse(String sql) {
        if (sql == null) return Optional.empty();
        String text = sql.trim();
        Matcher schema = CREATE_SCHEMA.matcher(text);
        if (schema.matches()) {
            String role = schema.group(3).trim();
            if (role.equalsIgnoreCase("default") || !role.startsWith("'")) throw new SpectrumSqlException("0A000", "IAM_ROLE DEFAULT is not supported");
            return Optional.of(new ExternalStatement.CreateSchema(identifier(schema.group(1)), schema.group(2).replace("''", "'"), unquote(role), text.toLowerCase(Locale.ROOT).contains("create external database if not exists")));
        }
        if (text.regionMatches(true, 0, "CREATE EXTERNAL", 0, 15)) return Optional.of(parseTable(text));
        Matcher dropTable = DROP_TABLE.matcher(text);
        if (dropTable.matches()) return Optional.of(new ExternalStatement.DropTable(identifier(dropTable.group(2)), identifier(dropTable.group(3)), dropTable.group(1) != null));
        Matcher dropSchema = DROP_SCHEMA.matcher(text);
        if (dropSchema.matches()) return Optional.of(new ExternalStatement.DropSchema(identifier(dropSchema.group(2)), dropSchema.group(1) != null));
        Matcher addPartitions = ADD_PARTITIONS.matcher(text);
        if (addPartitions.matches()) {
            List<ExternalStatement.PartitionSpec> partitions = parsePartitions(addPartitions.group(4));
            return partitions.isEmpty() ? Optional.empty() : Optional.of(new ExternalStatement.AddPartitions(
                    identifier(addPartitions.group(1)), identifier(addPartitions.group(2)),
                    addPartitions.group(3) != null, partitions));
        }
        return Optional.empty();
    }

    private ExternalStatement.CreateTable parseTable(String text) {
        Matcher matcher = CREATE_TABLE_HEAD.matcher(text);
        if (!matcher.find()) throw new SpectrumSqlException("0A000", "malformed CREATE EXTERNAL TABLE");
        int open = matcher.end() - 1;
        int close = matchingParen(text, open);
        if (close < 0) throw new SpectrumSqlException("0A000", "malformed CREATE EXTERNAL TABLE column list");
        String tail = text.substring(close + 1);
        List<ExternalStatement.ColumnDefinition> columns = columns(text.substring(open + 1, close));
        List<ExternalStatement.ColumnDefinition> partitions = List.of();
        Matcher partition = Pattern.compile("(?is).*?PARTITIONED\\s+BY\\s*\\((.*?)\\).*", Pattern.DOTALL).matcher(tail);
        if (partition.matches()) partitions = columns(partition.group(1));
        String location = value(tail, "LOCATION");
        if (location == null || !location.startsWith("s3://")) throw new SpectrumSqlException("22023", "external table location must be an s3:// URI");
        String serde = value(tail, "SERDE");
        String delimiter = value(tail, "TERMINATED BY");
        String stored = keywordValue(tail, "STORED AS");
        if (stored == null || !(stored.equalsIgnoreCase("PARQUET") || stored.equalsIgnoreCase("TEXTFILE"))) {
            throw new SpectrumSqlException("0A000", "unsupported external table format: " + stored);
        }
        ExternalStatement.TableFormat format = stored.equalsIgnoreCase("PARQUET")
                ? ExternalStatement.TableFormat.PARQUET
                : serde != null && serde.toLowerCase(Locale.ROOT).contains("json")
                ? ExternalStatement.TableFormat.JSON : ExternalStatement.TableFormat.TEXTFILE;
        Map<String, String> properties = properties(tail);
        return new ExternalStatement.CreateTable(identifier(matcher.group(1)), identifier(matcher.group(2)), columns, partitions, format, location, delimiter, serde, properties);
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) i++;
                    else quote = 0;
                }
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static List<ExternalStatement.ColumnDefinition> columns(String text) {
        List<ExternalStatement.ColumnDefinition> result = new ArrayList<>();
        for (String item : split(text)) { String[] parts = item.trim().split("\\s+", 2); if (parts.length < 2) throw new SpectrumSqlException("0A000", "malformed column definition"); result.add(new ExternalStatement.ColumnDefinition(identifier(parts[0]), parts[1].replace(" ", "").toLowerCase(Locale.ROOT))); }
        return result;
    }
    private static List<String> split(String text) { List<String> result = new ArrayList<>(); int depth = 0, start = 0; for (int i=0;i<text.length();i++){ char c=text.charAt(i); if(c=='('||c=='<')depth++; else if(c==')'||c=='>')depth--; else if(c==','&&depth==0){result.add(text.substring(start,i));start=i+1;} } result.add(text.substring(start)); return result; }
    private static String value(String text, String key) { Matcher m=Pattern.compile("(?is)"+Pattern.quote(key)+"\\s+'((?:''|[^'])*)'").matcher(text); return m.find()?m.group(1).replace("''", "'"):null; }
    private static String keywordValue(String text, String key) { Matcher m=Pattern.compile("(?is)"+Pattern.quote(key)+"\\s+([A-Za-z]+)").matcher(text); return m.find()?m.group(1):null; }
    private static Map<String,String> properties(String text){ Map<String,String> result=new LinkedHashMap<>(); Matcher m=Pattern.compile("(?is)(?:TBLPROPERTIES|TABLE\\s+PROPERTIES)\\s*\\((.*?)\\)").matcher(text); if(m.find()){Matcher p=Pattern.compile("'([^']*)'\\s*=\\s*'([^']*)'").matcher(m.group(1)); while(p.find()) result.put(p.group(1),p.group(2));} return result; }
    private static List<ExternalStatement.PartitionSpec> parsePartitions(String text) {
        List<ExternalStatement.PartitionSpec> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?is)PARTITION\\s*\\(([^)]*)\\)\\s*LOCATION\\s+'((?:''|[^'])*)'").matcher(text);
        int end = 0;
        while (matcher.find()) {
            if (!text.substring(end, matcher.start()).isBlank()) return List.of();
            Map<String, String> values = new LinkedHashMap<>();
            Matcher value = Pattern.compile("(?is)([^,=]+)=\\s*'((?:''|[^'])*)'\\s*(?:,|$)").matcher(matcher.group(1).trim() + ",");
            while (value.find()) values.put(identifier(value.group(1)), value.group(2).replace("''", "'"));
            if (values.isEmpty()) return List.of();
            result.add(new ExternalStatement.PartitionSpec(values, matcher.group(2).replace("''", "'")));
            end = matcher.end();
        }
        return end == text.length() || text.substring(end).isBlank() ? result : List.of();
    }
    private static String identifier(String value){String v=value.trim(); if(v.startsWith("\"")&&v.endsWith("\"")) return v.substring(1,v.length()-1).replace("\"\"","\""); return v.toLowerCase(Locale.ROOT);}
    private static String unquote(String value){String v=value.trim(); return v.startsWith("'")&&v.endsWith("'")?v.substring(1,v.length()-1).replace("''", "'"):v;}
}
