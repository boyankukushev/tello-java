package com.tello.core;

/** Direction argument for the {@code flip} command. */
public enum FlipDirection {
    LEFT("l"), RIGHT("r"), FORWARD("f"), BACK("b");

    private final String code;

    FlipDirection(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
