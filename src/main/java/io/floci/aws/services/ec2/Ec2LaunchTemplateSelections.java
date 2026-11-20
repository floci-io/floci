package io.floci.aws.services.ec2;

import jakarta.enterprise.context.RequestScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Keeps name resolution stable between IAM evaluation and the operation in the same request. */
@RequestScoped
public class Ec2LaunchTemplateSelections {

    private record Key(String account, String region, String name) {
    }

    /** A null id records that the name did not exist when IAM evaluated it. */
    record Selection(String id) {
    }

    private final Map<Key, Selection> selections = new HashMap<>();

    Selection resolve(String account, String region, String name, Supplier<String> lookup) {
        return selections.computeIfAbsent(new Key(account, region, name), ignored -> new Selection(lookup.get()));
    }

    Selection get(String account, String region, String name) {
        return selections.get(new Key(account, region, name));
    }
}
