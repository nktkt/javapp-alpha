package dev.javapp.runtime;

import java.util.Optional;
import java.util.function.Function;

public sealed interface Result<T, E> permits Result.Ok, Result.Err {
    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    boolean isOk();

    default boolean isErr() {
        return !isOk();
    }

    T orElse(T fallback);

    T orElseGet(Function<? super E, ? extends T> fallback);

    <U> Result<U, E> map(Function<? super T, ? extends U> mapper);

    <F> Result<T, F> mapError(Function<? super E, ? extends F> mapper);

    <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> mapper);

    Optional<T> okOptional();

    Optional<E> errOptional();

    record Ok<T, E>(T value) implements Result<T, E> {
        @Override
        public boolean isOk() {
            return true;
        }

        @Override
        public T orElse(T fallback) {
            return value;
        }

        @Override
        public T orElseGet(Function<? super E, ? extends T> fallback) {
            return value;
        }

        @Override
        public <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
            return Result.ok(mapper.apply(value));
        }

        @Override
        public <F> Result<T, F> mapError(Function<? super E, ? extends F> mapper) {
            return Result.ok(value);
        }

        @Override
        public <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> mapper) {
            return mapper.apply(value);
        }

        @Override
        public Optional<T> okOptional() {
            return Optional.ofNullable(value);
        }

        @Override
        public Optional<E> errOptional() {
            return Optional.empty();
        }
    }

    record Err<T, E>(E error) implements Result<T, E> {
        @Override
        public boolean isOk() {
            return false;
        }

        @Override
        public T orElse(T fallback) {
            return fallback;
        }

        @Override
        public T orElseGet(Function<? super E, ? extends T> fallback) {
            return fallback.apply(error);
        }

        @Override
        public <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
            return Result.err(error);
        }

        @Override
        public <F> Result<T, F> mapError(Function<? super E, ? extends F> mapper) {
            return Result.err(mapper.apply(error));
        }

        @Override
        public <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> mapper) {
            return Result.err(error);
        }

        @Override
        public Optional<T> okOptional() {
            return Optional.empty();
        }

        @Override
        public Optional<E> errOptional() {
            return Optional.ofNullable(error);
        }
    }
}

