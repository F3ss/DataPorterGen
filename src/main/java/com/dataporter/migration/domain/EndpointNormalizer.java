package com.dataporter.migration.domain;

import java.util.Set;
import java.util.TreeSet;

/** Pure string normalization of MongoDB cluster endpoints for same-cluster comparison. */
public final class EndpointNormalizer {
    private EndpointNormalizer() {}

    public static Set<String> clusterHosts(String uri) {
        String safe = uri == null ? "" : uri.trim();
        int scheme = safe.indexOf("://");
        if (scheme < 0) return Set.of();
        boolean srv = safe.substring(0, scheme).equalsIgnoreCase("mongodb+srv");
        String rest = safe.substring(scheme + 3);
        int credentials = rest.lastIndexOf('@');
        if (credentials >= 0) rest = rest.substring(credentials + 1);
        for (char marker : new char[] {'/', '?', '#'}) {
            int cut = rest.indexOf(marker);
            if (cut >= 0) rest = rest.substring(0, cut);
        }
        TreeSet<String> hosts = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String host : rest.split(",")) {
            host = host.trim();
            if (host.isEmpty()) continue;
            if (host.startsWith("[")) {
                int close = host.indexOf(']');
                if (close > 0) hosts.add(srv ? host.substring(0, close + 1)
                        : host.substring(0, close + 1) + defaultPort(host.substring(close + 1)));
            } else {
                int colon = host.lastIndexOf(':');
                if (colon > 0) hosts.add(srv ? host.substring(0, colon)
                        : host.substring(0, colon) + defaultPort(host.substring(colon)));
                else hosts.add(srv ? host : host + ":27017");
            }
        }
        return hosts;
    }

    private static String defaultPort(String port) {
        return port.isEmpty() || ":27017".equalsIgnoreCase(port) ? ":27017" : port.toLowerCase();
    }
}
