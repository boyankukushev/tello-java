package com.tello.core;

import java.util.Map;

/** IMU angular acceleration data returned by {@code acceleration?}, in units of 0.001g. */
public record Acceleration(double x, double y, double z) {

    static Acceleration parse(String raw) {
        Map<String, String> fields = TelloResponses.parseKeyValues(raw);
        if (fields.containsKey("agx")) {
            return new Acceleration(
                    Double.parseDouble(fields.get("agx")),
                    Double.parseDouble(fields.get("agy")),
                    Double.parseDouble(fields.get("agz")));
        }
        double[] numbers = TelloResponses.parseNumbers(raw);
        if (numbers.length < 3) {
            throw new TelloException("Unrecognized acceleration response: " + raw);
        }
        return new Acceleration(numbers[0], numbers[1], numbers[2]);
    }
}
