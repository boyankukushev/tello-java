package com.tello.core;

/** Unchecked exception for command failures, timeouts, and protocol errors. */
public class TelloException extends RuntimeException {

    public TelloException(String message) {
        super(message);
    }

    public TelloException(String message, Throwable cause) {
        super(message, cause);
    }
}
