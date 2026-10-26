package io.github.hectorvent.floci.services.dynamodb;

import com.amazon.ion.IonType;
import com.amazon.ion.IonWriter;
import com.amazon.ion.system.IonTextWriterBuilder;
import com.amazon.ion.system.IonWriterBuilder.InitialIvmHandling;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamoDbIonTextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void exportLineIsTheLineAwsWrites() throws IOException {
        JsonNode item = MAPPER.readTree("""
            {"pk": {"S": "types"}, "sk": {"N": "1"}, "b": {"B": "AAEC/w=="}, "n_lead": {"N": "007"},
             "ns": {"NS": ["2.50", "1E+3", "3", "1"]}, "null": {"S": "kw null"}, "true": {"S": "kw true"},
             "s_unicode": {"S": "日本語 é 😀"},
             "l": {"L": [{"S": "x"}, {"N": "1"}, {"BOOL": true}, {"NULL": true}, {"L": []}, {"M": {}},
                         {"M": {"a": {"N": "1"}}}, {"SS": ["z"]}]},
             "s_empty": {"S": ""}, "m": {"M": {"a-b": {"N": "1"}, "nested": {"M": {"deeper": {"S": "v"}}}}},
             "bool_t": {"BOOL": true}, "n_int": {"N": "103"}, "bs": {"BS": ["AA==", "eA=="]}, "s": {"S": "hello"},
             "a-b": {"S": "dash"}, "$x": {"S": "dollar"}, "n_zero": {"N": "0"},
             "n_38": {"N": "12345678901234567890123456789012345678"}, "n_trail": {"N": "123.4500"},
             "s_quote": {"S": "he said \\"hi\\"\\n\\ttab \\\\ back 'single'"}, "n_point": {"N": "10.0"},
             "n_2000": {"N": "2000"}, "m_empty": {"M": {}}, "ss": {"SS": ["a", "b", "c d"]},
             "n_big": {"N": "1E+125"}, "bool_f": {"BOOL": false}, "$10": {"S": "dollar digits"},
             "with space": {"S": "sp"}, "n_frac": {"N": "0.5"}, "nul": {"NULL": true}, "n_small": {"N": "1E-130"},
             "n_600": {"N": "600"}, "'q": {"S": "quote"}, "b_empty": {"B": ""}, "n_negfrac": {"N": "-0.001"},
             "n_neg": {"N": "-1"}, "n_e": {"N": "1.5e3"}, "+inf": {"S": "inf"},
             "s_ctrl": {"S": "\\u0001\\u001f\\u007f\\u0080"}, "a.b": {"N": "2"}, "日本": {"S": "jp"},
             "Upper": {"S": "upper"}, "1abc": {"S": "digit"}, "_u": {"S": "underscore"}, "l_empty": {"L": []},
             "nan": {"S": "kw nan"}, "false": {"S": "kw false"}}
            """);

        assertEquals("""
            $ion_1_0 {Item:{pk:"types",sk:1.,b:{{AAEC/w==}},n_lead:7.,ns:$dynamodb_NS::[2.5,1d3,3.,1.],\
            'null':"kw null",'true':"kw true",s_unicode:"日本語 é 😀",\
            l:["x",1.,true,null,[],{},{a:1.},$dynamodb_SS::["z"]],s_empty:"",m:{'a-b':1.,nested:{deeper:"v"}},\
            bool_t:true,n_int:103.,bs:$dynamodb_BS::[{{AA==}},{{eA==}}],s:"hello",'a-b':"dash",$x:"dollar",\
            n_zero:0.,n_38:12345678901234567890123456789012345678.,n_trail:123.45,\
            s_quote:"he said \\"hi\\"\\n\\ttab \\\\ back 'single'",n_point:1d1,n_2000:2d3,m_empty:{},\
            ss:$dynamodb_SS::["a","b","c d"],n_big:1d125,bool_f:false,'$10':"dollar digits",'with space':"sp",\
            n_frac:0.5,nul:null,n_small:1d-130,n_600:6d2,'\\'q':"quote",b_empty:{{}},n_negfrac:-0.001,n_neg:-1.,\
            n_e:15d2,'+inf':"inf",s_ctrl:"\\x01\\x1f\\x7f\\x80",'a.b':2.,'日本':"jp",Upper:"upper",'1abc':"digit",\
            _u:"underscore",l_empty:[],'nan':"kw nan",'false':"kw false"}}""", DynamoDbIonText.exportLine(item));
    }

    @Test
    void exportLineIsWhatIonJavaWrites() throws IOException {
        JsonNode item = MAPPER.readTree("""
            {"pk": {"S": "edge"}, "$": {"S": "lone dollar"}, "$0": {"S": "symbol id"}, "nulls": {"S": "no keyword"},
             "NULL": {"S": "upper case"}, "+-": {"S": "operators"}, "tab\\tname": {"S": "t"},
             "quote\\"name": {"S": "q"}, "back\\\\slash": {"S": "b"}, "é": {"S": "latin"}, "😀": {"S": "emoji"},
             "ctrl": {"S": "\\u0000\\u0007\\b\\t\\n\\u000b\\f\\r\\u001b\\u007f\\u009f\\u00a0\\u00ff\\u0100 😀 ' \\""},
             "numbers": {"NS": ["0", "-0", "0.000001", "0.0000001", "123456.789", "-1E+125", "1E-130", "-1E-130",
                                "9.9999999999999999999999999999999999999E+125", "100", "0.1", "12.0", "-12.5"]},
             "nested": {"L": [{"L": [{"M": {"x y": {"SS": ["'", "\\""]}}}]}, {"BS": ["", "AAEC/w=="]}]},
             "blob": {"B": "////"}}
            """);

        assertEquals(ionJava(item), DynamoDbIonText.exportLine(item));
    }

    private static String ionJava(JsonNode item) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (IonWriter writer = IonTextWriterBuilder.standard().withInitialIvmHandling(InitialIvmHandling.ENSURE).build(out)) {
            writer.stepIn(IonType.STRUCT);
            writer.setFieldName("Item");
            writeStruct(writer, item);
            writer.stepOut();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void writeStruct(IonWriter writer, JsonNode attributes) throws IOException {
        writer.stepIn(IonType.STRUCT);
        for (Map.Entry<String, JsonNode> attribute : attributes.properties()) {
            writer.setFieldName(attribute.getKey());
            writeValue(writer, attribute.getValue());
        }
        writer.stepOut();
    }

    private static void writeValue(IonWriter writer, JsonNode value) throws IOException {
        Map.Entry<String, JsonNode> typed = value.properties().iterator().next();
        JsonNode content = typed.getValue();
        switch (typed.getKey()) {
            case "S" -> writer.writeString(content.asText());
            case "N" -> writer.writeDecimal(new BigDecimal(content.asText()).stripTrailingZeros());
            case "B" -> writer.writeBlob(Base64.getDecoder().decode(content.asText()));
            case "BOOL" -> writer.writeBool(content.asBoolean());
            case "NULL" -> writer.writeNull();
            case "SS", "NS", "BS" -> {
                writer.setTypeAnnotations("$dynamodb_" + typed.getKey());
                writer.stepIn(IonType.LIST);
                for (JsonNode member : content) {
                    writeValue(writer, MAPPER.createObjectNode().set(typed.getKey().substring(0, 1), member));
                }
                writer.stepOut();
            }
            case "L" -> {
                writer.stepIn(IonType.LIST);
                for (JsonNode member : content) {
                    writeValue(writer, member);
                }
                writer.stepOut();
            }
            case "M" -> writeStruct(writer, content);
            default -> throw new IllegalArgumentException(typed.getKey());
        }
    }
}
