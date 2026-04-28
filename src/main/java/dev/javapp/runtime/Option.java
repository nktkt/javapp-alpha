package dev.javapp.runtime;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface Option<T> permits Option.Some, Option.None {
    static <T> Option<T> some(T value) {
        return new Some<>(value);
    }

    static <T> Option<T> none() {
        return None.instance();
    }

    static <T> Option<T> ofNullable(T value) {
        return value == null ? none() : some(value);
    }

    boolean isSome();

    default boolean isNone() {
        return !isSome();
    }

    T get();

    T orElse(T fallback);

    T orElseGet(Supplier<? extends T> fallback);

    <U> Option<U> map(Function<? super T, ? extends U> mapper);

    <U> Option<U> flatMap(Function<? super T, Option<U>> mapper);

    Optional<T> toOptional();

    record Some<T>(T value) implements Option<T> {
        @Override
        public boolean isSome() {
            return true;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public T orElse(T fallback) {
            return value;
        }

        @Override
        public T orElseGet(Supplier<? extends T> fallback) {
            return value;
        }

        @Override
        public <U> Option<U> map(Function<? super T, ? extends U> mapper) {
            return Option.ofNullable(mapper.apply(value));
        }

        @Override
        public <U> Option<U> flatMap(Function<? super T, Option<U>> mapper) {
            return mapper.apply(value);
        }

        @Override
        public Optional<T> toOptional() {
            return Optional.ofNullable(value);
        }
    }

    final class None<T> implements Option<T> {
        private static final None<?> INSTANCE = new None<>();

        private None() {
        }

        @SuppressWarnings("unchecked")
        static <T> None<T> instance() {
            return (None<T>) INSTANCE;
        }

        @Override
        public boolean isSome() {
            return false;
        }

        @Override
        public T get() {
            throw new NoSuchElementException("Option.None has no value");
        }

        @Override
        public T orElse(T fallback) {
            return fallback;
        }

        @Override
        public T orElseGet(Supplier<? extends T> fallback) {
            return fallback.get();
        }

        @Override
        public <U> Option<U> map(Function<? super T, ? extends U> mapper) {
            return Option.none();
        }

        @Override
        public <U> Option<U> flatMap(Function<? super T, Option<U>> mapper) {
            return Option.none();
        }

        @Override
        public Optional<T> toOptional() {
            return Optional.empty();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof None<?>;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return "None";
        }
    }
}

