package io.floci.aws.services.stepfunctions;

import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.Parser;
import com.dashjoin.jsonata.Utils;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps indexed constructed-array paths consistent with JSONata 2.0.6's missing-value semantics. */
@RegisterForReflection(targets = Parser.Symbol.class, fields = true)
final class JsonataConstructedArrayPaths {

    private static final Field TYPE = field("type");
    private static final Field STEPS = field("steps");
    private static final Field KEEP_ARRAY = field("keepArray");

    private JsonataConstructedArrayPaths() {
    }

    static void install(Jsonata.Frame frame) {
        Set<Parser.Symbol> firstSteps = Collections.newSetFromMap(new IdentityHashMap<>());
        Jsonata.EntryCallback entry = (Jsonata.EntryCallback) frame.lookup("__evaluate_entry");
        Jsonata.ExitCallback exit = (Jsonata.ExitCallback) frame.lookup("__evaluate_exit");
        frame.setEvaluateEntryCallback((expression, input, environment) -> {
            entry.callback(expression, input, environment);
            Parser.Symbol first = firstConstructedStep(expression);
            if (first != null) {
                firstSteps.add(first);
            }
        });
        frame.setEvaluateExitCallback((expression, input, environment, result) -> {
            exit.callback(expression, input, environment, result);
            if (firstSteps.contains(expression) && result instanceof Utils.JList<?> sequence
                    && sequence.sequence && !sequence.keepSingleton && sequence.size() == 1
                    && hasNoLength(sequence.getFirst())) {
                // The reference path iterates the reduced object's absent length and returns
                // nothing. Dashjoin instead casts that object to List. Empty only this first
                // step's sequence before reduction, preserving grouped and forced-array paths.
                sequence.clear();
            }
        });
    }

    private static boolean hasNoLength(Object value) {
        if (value instanceof Map<?, ?> object) {
            return !object.containsKey("length");
        }
        return value instanceof Number || value instanceof Boolean;
    }

    private static Parser.Symbol firstConstructedStep(Parser.Symbol expression) {
        try {
            if ("path".equals(TYPE.get(expression)) && STEPS.get(expression) instanceof List<?> steps
                    && steps.size() > 1 && steps.getFirst() instanceof Parser.Symbol first
                    && first.consarray && !KEEP_ARRAY.getBoolean(first)) {
                return first;
            }
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot inspect JSONata constructed-array path", e);
        }
    }

    private static Field field(String name) {
        try {
            Field field = Parser.Symbol.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
