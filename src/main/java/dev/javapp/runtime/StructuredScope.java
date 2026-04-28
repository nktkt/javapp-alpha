package dev.javapp.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Duration;

public final class StructuredScope implements AutoCloseable {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Future<?>> futures = Collections.synchronizedList(new ArrayList<>());
    private final long deadlineNanos;
    private volatile boolean closed;
    private volatile boolean timedOut;

    private StructuredScope() {
        this.deadlineNanos = 0;
    }

    private StructuredScope(Duration timeout) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long timeoutNanos = timeout.toNanos();
        this.deadlineNanos = System.nanoTime() + timeoutNanos;
    }

    public static StructuredScope open() {
        return new StructuredScope();
    }

    public static StructuredScope open(Duration timeout) {
        return new StructuredScope(timeout);
    }

    public <T> Task<T> fork(ThrowingSupplier<T> supplier) {
        return submit(supplier::get);
    }

    public Task<Void> forkVoid(ThrowingRunnable runnable) {
        return submit(() -> {
            runnable.run();
            return null;
        });
    }

    @Override
    public void close() {
        closed = true;
        cancelUnfinished();
        executor.shutdownNow();
    }

    private <T> Task<T> submit(Callable<T> callable) {
        if (closed) {
            throw new IllegalStateException("structured scope is already closed");
        }
        Future<T> future = executor.submit(callable);
        futures.add(future);
        return new Task<>(this, future);
    }

    private boolean hasTimeout() {
        return deadlineNanos != 0;
    }

    private long remainingNanos() {
        return deadlineNanos - System.nanoTime();
    }

    private void markTimedOut() {
        timedOut = true;
        cancelUnfinished();
    }

    private void cancelUnfinished() {
        synchronized (futures) {
            for (Future<?> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }
    }

    public static final class Task<T> {
        private final StructuredScope scope;
        private final Future<T> future;

        private Task(StructuredScope scope, Future<T> future) {
            this.scope = scope;
            this.future = future;
        }

        public T await() {
            try {
                if (scope.timedOut) {
                    throw new StructuredTaskException("structured scope timed out");
                }
                if (!scope.hasTimeout()) {
                    return future.get();
                }

                long remaining = scope.remainingNanos();
                if (remaining <= 0) {
                    scope.markTimedOut();
                    throw new StructuredTaskException("structured scope timed out");
                }
                return future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new StructuredTaskException("structured task interrupted", ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new StructuredTaskException("structured task failed", cause);
            } catch (TimeoutException ex) {
                scope.markTimedOut();
                throw new StructuredTaskException("structured scope timed out", ex);
            }
        }

        public boolean isDone() {
            return future.isDone();
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
