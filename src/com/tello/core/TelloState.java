package com.tello.core;

import java.util.Locale;
import java.util.Map;

/**
 * A single telemetry snapshot pushed by Tello on the state port (UDP 8890), per SDK section 5:
 * {@code pitch:%d;roll:%d;yaw:%d;vgx:%d;vgy:%d;vgz:%d;templ:%d;temph:%d;tof:%d;h:%d;bat:%d;
 * baro:%.2f;time:%d;agx:%.2f;agy:%.2f;agz:%.2f;}
 */
public record TelloState(
        int pitch, int roll, int yaw,
        int vgx, int vgy, int vgz,
        int templ, int temph,
        int tof, int h, int bat,
        double baro, int time,
        double agx, double agy, double agz,
        String raw) {

    public static TelloState parse(String raw) {
        Map<String, String> fields = TelloResponses.parseKeyValues(raw);
        return new TelloState(
                intOf(fields, "pitch"), intOf(fields, "roll"), intOf(fields, "yaw"),
                intOf(fields, "vgx"), intOf(fields, "vgy"), intOf(fields, "vgz"),
                intOf(fields, "templ"), intOf(fields, "temph"),
                intOf(fields, "tof"), intOf(fields, "h"), intOf(fields, "bat"),
                doubleOf(fields, "baro"), intOf(fields, "time"),
                doubleOf(fields, "agx"), doubleOf(fields, "agy"), doubleOf(fields, "agz"),
                raw.trim());
    }

    private static int intOf(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static double doubleOf(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null ? 0.0 : Double.parseDouble(value);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "battery=%d%% height=%dcm tof=%dcm pitch=%d roll=%d yaw=%d speed=(%d,%d,%d) temp=%d-%dC baro=%.2f time=%ds accel=(%.2f,%.2f,%.2f)",
                bat, h, tof, pitch, roll, yaw, vgx, vgy, vgz, templ, temph, baro, time, agx, agy, agz);
    }
}
