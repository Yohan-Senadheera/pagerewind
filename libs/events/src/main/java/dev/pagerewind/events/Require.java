package dev.pagerewind.events;

import java.util.Objects;

final class Require {

    private Require() {
    }

    static <T> T nonNull(T value, String name) {
        return Objects.requireNonNull(value, () -> name + " must not be null");
    }

    static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1");
        }
        return value;
    }
}
