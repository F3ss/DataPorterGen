package com.dataporter.generation.application;

import com.dataporter.generation.domain.GenerationRule.DerivedDate;
import com.dataporter.generation.domain.GenerationRule.FixedDate;
import com.dataporter.generation.domain.GenerationRule.RandomDateRange;
import com.dataporter.generation.domain.SharedDateDefinition;
import com.dataporter.shared.error.ConfigurationException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

final class SharedDateValidator {
    void validate(Map<String, SharedDateDefinition> definitions) {
        Map<String, DateBounds> bounds = new HashMap<>();
        for (String start : definitions.keySet())
            if (!bounds.containsKey(start)) resolve(start, definitions, bounds);
    }

    private static void resolve(String start, Map<String, SharedDateDefinition> definitions, Map<String, DateBounds> bounds) {
        var pending = new ArrayDeque<String>();
        var visiting = new HashSet<String>();
        String name = start;
        while (!bounds.containsKey(name)) {
            SharedDateDefinition definition = definitions.get(name);
            if (definition == null)
                throw new ConfigurationException("Unknown shared date " + name + " referenced by " + pending.getFirst());
            switch (definition.source()) {
                case DerivedDate derived -> {
                    if (!visiting.add(name)) throw new ConfigurationException("Cyclic shared date dependency involving " + name);
                    pending.push(name);
                    name = derived.sourceName();
                }
                case FixedDate fixed -> bounds.put(name, DateBounds.checked(name, fixed.value(), fixed.value()));
                case RandomDateRange range -> bounds.put(name, DateBounds.checked(name, range.from(), range.to()));
                default -> throw new IllegalStateException("Unexpected shared date source");
            }
        }

        while (!pending.isEmpty()) {
            String derivedName = pending.pop();
            DerivedDate derived = (DerivedDate) definitions.get(derivedName).source();
            bounds.put(derivedName, bounds.get(derived.sourceName()).shift(derivedName, derived));
        }
    }

    private record DateBounds(Instant from, Instant to) {
        static DateBounds checked(String name, Instant from, Instant to) {
            try {
                return new DateBounds(Instant.ofEpochMilli(from.toEpochMilli()), Instant.ofEpochMilli(to.toEpochMilli()));
            } catch (ArithmeticException e) {
                throw outsideBsonRange(name, e);
            }
        }

        DateBounds shift(String name, DerivedDate derived) {
            try {
                Instant lower = derived.shift(from);
                Instant upper = derived.shift(to);
                if (derived.offsetYears() != 0) {
                    Instant nextDay = from.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
                    Instant lastDay = to.truncatedTo(ChronoUnit.DAYS);
                    if (!nextDay.isAfter(lastDay)) {
                        // Calendar years can map February 28 and 29 onto the same date.
                        Instant nextMidnight = derived.shift(nextDay);
                        Instant previousDayEnd = derived.shift(lastDay.minusMillis(1));
                        if (nextMidnight.isBefore(lower)) lower = nextMidnight;
                        if (previousDayEnd.isAfter(upper)) upper = previousDayEnd;
                    }
                }
                return checked(name, lower, upper);
            } catch (DateTimeException | ArithmeticException e) {
                throw outsideBsonRange(name, e);
            }
        }
    }

    private static ConfigurationException outsideBsonRange(String name, RuntimeException cause) {
        return new ConfigurationException("Shared date " + name + " exceeds the BSON Date range", cause);
    }
}
