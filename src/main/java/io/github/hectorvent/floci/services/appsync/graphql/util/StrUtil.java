package io.github.hectorvent.floci.services.appsync.graphql.util;

import java.text.Normalizer;

public class StrUtil {

    public String toUpper(String s) {
        if (s == null) return "";
        return s.toUpperCase();
    }

    public String toLower(String s) {
        if (s == null) return "";
        return s.toLowerCase();
    }

    public String toReplace(String s, String target, String replacement) {
        if (s == null || target == null || replacement == null) return s != null ? s : "";
        return s.replace(target, replacement);
    }

    public String normalize(String s, String form) {
        if (s == null) return "";
        if (form == null) form = "nfc";
        return Normalizer.normalize(s, Normalizer.Form.valueOf(form.toUpperCase()));
    }
}
