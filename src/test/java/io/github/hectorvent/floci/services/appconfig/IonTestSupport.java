package io.github.hectorvent.floci.services.appconfig;

import com.amazon.ion.IonDatagram;
import com.amazon.ion.IonSystem;
import com.amazon.ion.SymbolTable;
import com.amazon.ion.system.IonSystemBuilder;
import com.amazon.ion.system.SimpleCatalog;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Reads Ion with Amazon's own library, so the tests do not check the hand-written encoder against itself. AWS's shared
 * {@code ops} and {@code anns} tables are registered with placeholder names except {@code eq}, the operator the encoder
 * emits, so the stream's imports and symbol ids resolve exactly as in a real consumer.
 */
final class IonTestSupport {

    private static final IonSystem SYSTEM = system();

    private IonTestSupport() {}

    static IonDatagram decode(byte[] ion) {
        return SYSTEM.getLoader().load(ion);
    }

    static IonDatagram parse(String text) {
        return SYSTEM.getLoader().load(text);
    }

    private static IonSystem system() {
        IonSystem plain = IonSystemBuilder.standard().build();
        SimpleCatalog catalog = new SimpleCatalog();
        catalog.putTable(sharedTable(plain, "ops", 23, 4, "eq"));
        catalog.putTable(sharedTable(plain, "anns", 12, 0, null));
        return IonSystemBuilder.standard().withCatalog(catalog).build();
    }

    private static SymbolTable sharedTable(IonSystem ion, String name, int size, int namedPosition, String named) {
        List<String> symbols = IntStream.rangeClosed(1, size)
                .mapToObj(i -> i == namedPosition ? named : name + i)
                .toList();
        return ion.newSharedSymbolTable(name, 1, symbols.iterator());
    }
}
