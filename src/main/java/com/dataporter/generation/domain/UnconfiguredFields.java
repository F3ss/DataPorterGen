package com.dataporter.generation.domain;

/** What happens to template fields that have no configured rule in a collection. */
public enum UnconfiguredFields {
    /** Copy template values as-is (default; byte-identical to the historical behavior). */
    SNAPSHOT,
    /** Keep only configured fields plus /_id, ref/DateRef targets and the auto-resolved _id source. */
    OMIT,
    /** Keep all template fields, replacing unconfigured values with type defaults (0, "", false, null, []). */
    DEFAULTS,
    /** Keep all template fields, replacing unconfigured values with same-size deterministic randoms. */
    RANDOM
}
