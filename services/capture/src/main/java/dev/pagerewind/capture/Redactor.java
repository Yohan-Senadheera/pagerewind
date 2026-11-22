package dev.pagerewind.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials from log lines before they are kept anywhere. Applied to every line as it enters
 * the buffer, so nothing unredacted is ever stored or exported.
 */
final class Redactor {

    static final String MASK = "[REDACTED]";

    private record Rule(Pattern pattern, Function<MatchResult, String> replacement) {
    }

    private static final List<Rule> DEFAULTS = List.of(
            // "Authorization: Bearer xyz", "authorization=Basic xyz", "Authorization: xyz"
            new Rule(Pattern.compile("(?i)(authorization\\s*[:=]\\s*)((?:bearer|basic|token)\\s+)?[^\\s,;\"']+"),
                    m -> m.group(1) + (m.group(2) == null ? "" : m.group(2)) + MASK),
            // JSON web tokens anywhere in a line
            new Rule(Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"), m -> MASK),
            // password=..., "secret": "...", api_key: ..., client_secret=...
            new Rule(Pattern.compile("(?i)([\"']?(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key"
                    + "|client[_-]?secret)[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&]+)"),
                    m -> m.group(1) + quoteLike(m.group(2))),
            // credentials in URLs: scheme://user:password@host
            new Rule(Pattern.compile("(?i)([a-z][a-z0-9+.-]*://[^:/\\s@]+:)[^@\\s/]+@"), m -> m.group(1) + MASK + "@"));

    private final List<Rule> rules;

    Redactor(List<String> extraPatterns) {
        List<Rule> all = new ArrayList<>(DEFAULTS);
        for (String extra : extraPatterns) {
            all.add(new Rule(Pattern.compile(extra), m -> MASK));
        }
        this.rules = List.copyOf(all);
    }

    String redact(String line) {
        String result = line;
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(result);
            result = matcher.replaceAll(m -> Matcher.quoteReplacement(rule.replacement().apply(m)));
        }
        return result;
    }

    /** Keeps the quotes of a quoted value, so the line stays valid JSON or YAML. */
    private static String quoteLike(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"))) {
            char quote = value.charAt(0);
            return quote + MASK + quote;
        }
        return MASK;
    }
}
