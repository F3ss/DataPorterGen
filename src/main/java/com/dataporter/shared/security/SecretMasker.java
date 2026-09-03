package com.dataporter.shared.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SecretMasker {
    private static final Pattern URI = Pattern.compile("mongodb(?:\\+srv)?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDENTIALS = Pattern.compile("(mongodb(?:\\+srv)?://)[^/@\\s]+@", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_QUERY = Pattern.compile("(?i)(tlsCertificateKeyFile|tlsCAFile|password|token)=([^&\\s]+)");

    private SecretMasker() {}

    public static String sanitize(String value) {
        if (value == null) return "";
        String safe = CREDENTIALS.matcher(value).replaceFirst("$1");
        Matcher matcher = URI.matcher(safe);
        StringBuilder stripped = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(stripped, Matcher.quoteReplacement(withoutQueryAndFragment(matcher.group())));
        matcher.appendTail(stripped);
        return stripped.toString();
    }

    private static String withoutQueryAndFragment(String uri) {
        int query = uri.indexOf('?');
        int fragment = uri.indexOf('#');
        int cut = query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
        return cut < 0 ? uri : uri.substring(0, cut);
    }

    public static String redact(String text) {
        if (text == null) return "";
        Matcher matcher = URI.matcher(text);
        StringBuilder safe = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(safe, Matcher.quoteReplacement(sanitize(matcher.group())));
        matcher.appendTail(safe);
        return SENSITIVE_QUERY.matcher(safe).replaceAll("$1=***");
    }
}
