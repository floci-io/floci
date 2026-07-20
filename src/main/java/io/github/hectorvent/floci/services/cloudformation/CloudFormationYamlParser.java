package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses CloudFormation YAML templates, properly converting shorthand intrinsic
 * function tags (!Ref, !Sub, !GetAtt, !If, !Join, !Select, etc.) to their
 * long-form map equivalents ({"Ref": ...}, {"Fn::Sub": ...}, etc.).
 */
public class CloudFormationYamlParser {

    private final ObjectMapper objectMapper;

    CloudFormationYamlParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode parse(String yamlContent) throws Exception {
        LoaderOptions opts = new LoaderOptions();
        opts.setMaxAliasesForCollections(200);
        Yaml yaml = new Yaml(new CfnConstructor(opts));
        Object data = yaml.load(yamlContent);
        return objectMapper.convertValue(data, JsonNode.class);
    }

    /**
     * SnakeYAML constructor that maps CloudFormation YAML shorthand tags to
     * their long-form intrinsic function maps.
     */
    private static class CfnConstructor extends SafeConstructor {

        CfnConstructor(LoaderOptions opts) {
            super(opts);
            // Register all CloudFormation shorthand tags
            register("!Ref",        n -> scalarMap("Ref", scalar(n)));
            register("!Sub",        n -> fnMap("Fn::Sub", n));
            register("!GetAtt", this::fnGetAtt);
            register("!If",         n -> fnMap("Fn::If", n));
            register("!Join",       n -> fnMap("Fn::Join", n));
            register("!Select",     n -> fnMap("Fn::Select", n));
            register("!Base64",     n -> fnMap("Fn::Base64", n));
            register("!FindInMap",  n -> fnMap("Fn::FindInMap", n));
            register("!Split",      n -> fnMap("Fn::Split", n));
            register("!Cidr",       n -> fnMap("Fn::Cidr", n));
            register("!GetAZs",     n -> fnMap("Fn::GetAZs", n));
            register("!ImportValue",n -> fnMap("Fn::ImportValue", n));
            register("!Condition",  n -> scalarMap("Condition", scalar(n)));
            register("!And",        n -> fnMap("Fn::And", n));
            register("!Or",         n -> fnMap("Fn::Or", n));
            register("!Not",        n -> fnMap("Fn::Not", n));
            register("!Equals",     n -> fnMap("Fn::Equals", n));
            register("!Contains",   n -> fnMap("Fn::Contains", n));
            register("!Length",     n -> fnMap("Fn::Length", n));
            register("!ToJsonString", n -> fnMap("Fn::ToJsonString", n));
            register("!Transform",  n -> fnMap("Fn::Transform", n));
        }

        private void register(String tag, NodeConverter converter) {
            yamlConstructors.put(new Tag(tag), new Construct() {
                @Override
                public Object construct(Node node) {
                    return converter.convert(node);
                }
                @Override
                public void construct2ndStep(Node node, Object data) {}
            });
        }

        private String scalar(Node n) {
            return (n instanceof ScalarNode s) ? s.getValue() : n.toString();
        }

        private Map<String, Object> scalarMap(String key, String value) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(key, value);
            return map;
        }

        private Object fnMap(String fnName, Node n) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(fnName, construct(n));
            return map;
        }

        private Object fnGetAtt(Node n) {
            String raw = scalar(n);
            // !GetAtt Resource.Attribute
            int dot = raw.indexOf('.');
            List<String> parts = new ArrayList<>();
            if (dot > 0) {
                parts.add(raw.substring(0, dot));
                parts.add(raw.substring(dot + 1));
            } else {
                parts.add(raw);
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("Fn::GetAtt", parts);
            return map;
        }

        private Object construct(Node n) {
            if (n instanceof ScalarNode s) {
                return constructScalar(s);
            } else if (n instanceof SequenceNode seq) {
                return constructSequence(seq);
            } else if (n instanceof MappingNode m) {
                return constructMapping(m);
            }
            return constructObject(n);
        }

        @FunctionalInterface
        private interface NodeConverter {
            Object convert(Node node);
        }
    }
}
