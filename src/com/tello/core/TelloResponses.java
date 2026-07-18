package com.tello.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing helpers shared by the record types that decode Tello's text responses. */
final class TelloResponses {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    // Tolerates a letter-only key glued directly to its numeric value with no colon, e.g. "vgy20"
    // instead of "vgy:20" - the official SDK 1.3 documentation's own example has exactly this typo
    // in its vgy field, unlike every other field in the same string.
    private static final Pattern KEY_VALUE_NO_COLON = Pattern.compile("([a-zA-Z]+)(-?\\d+(?:\\.\\d+)?)");

    private TelloResponses() {
    }

    /** Parses a {@code key:value;key:value;...} string, as used by the state stream and some read commands. */
    static Map<String, String> parseKeyValues(String raw) {
        Map<String, String> fields = new HashMap<>();
        for (String part : raw.trim().split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] kv = trimmed.split(":", 2);
            if (kv.length == 2) {
                fields.put(kv[0].trim(), kv[1].trim());
                continue;
            }
            Matcher noColon = KEY_VALUE_NO_COLON.matcher(trimmed);
            if (noColon.matches()) {
                fields.put(noColon.group(1), noColon.group(2));
            }
        }
        return fields;
    }

    /** Extracts every number found in the string, in order; used as a fallback for unlabeled responses. */
    static double[] parseNumbers(String raw) {
        Matcher matcher = NUMBER.matcher(raw);
        List<Double> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(Double.parseDouble(matcher.group()));
        }
        double[] result = new double[numbers.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = numbers.get(i);
        }
        return result;
    }
}
