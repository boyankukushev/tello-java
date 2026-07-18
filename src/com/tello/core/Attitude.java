package com.tello.core;

import java.util.Map;

/** IMU attitude data returned by {@code attitude?}: pitch, roll and yaw in degrees. */
public record Attitude(int pitch, int roll, int yaw) {

    static Attitude parse(String raw) {
        Map<String, String> fields = TelloResponses.parseKeyValues(raw);
        if (fields.containsKey("pitch")) {
            return new Attitude(
                    Integer.parseInt(fields.get("pitch")),
                    Integer.parseInt(fields.get("roll")),
                    Integer.parseInt(fields.get("yaw")));
        }
        double[] numbers = TelloResponses.parseNumbers(raw);
        if (numbers.length < 3) {
            throw new TelloException("Unrecognized attitude response: " + raw);
        }
        return new Attitude((int) numbers[0], (int) numbers[1], (int) numbers[2]);
    }
}
