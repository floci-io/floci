package io.github.hectorvent.floci.services.redshift.spectrum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SpectrumRow(List<String> values) {

    public SpectrumRow {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }
}
