package com.dataporter.shared.domain;

import java.util.regex.Pattern;

public final class WriteConcerns {
    private static final Pattern UNACKNOWLEDGED = Pattern.compile("(?i)[?&]w=0(?:&|$)");

    private WriteConcerns() {}

    public static boolean unacknowledged(String uri) {
        return uri != null && UNACKNOWLEDGED.matcher(uri).find();
    }
}
