package dev.javapp.runtime;

public final class StructuredTaskException extends RuntimeException {
    public StructuredTaskException(String message) {
        super(message);
    }

    public StructuredTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
