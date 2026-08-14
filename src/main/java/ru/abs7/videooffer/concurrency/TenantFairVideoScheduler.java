package ru.abs7.videooffer.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Small in-process fair scheduler for CPU / IO heavy video work.
 *
 * The database remains the durable source of truth (QUEUED/PREPARING and
 * UPLOADED/PROCESSING states are recovered after restart). This scheduler only
 * decides which already-persisted job gets one of the scarce heavy workers.
 * It never blocks a worker waiting for another tenant and therefore one noisy
 * tenant cannot occupy the whole pool with semaphore waiters.
 */
@Component
public class TenantFairVideoScheduler {
    private static final Logger log = LoggerFactory.getLogger(TenantFairVideoScheduler.class);
    private static final long LEGACY_TENANT_KEY = 0L;

    private final ThreadPoolTaskExecutor executor;
    private final int globalLimit;
    private final int perTenantLimit;
    private final int queueCapacity;

    private final Object monitor = new Object();
    private final Map<Long, ArrayDeque<QueuedTask>> queues = new LinkedHashMap<>();
    private final Map<Long, Integer> runningByTenant = new HashMap<>();
    private final ArrayDeque<Long> rotation = new ArrayDeque<>();
    private final Set<String> knownTaskKeys = new HashSet<>();
    private int runningGlobal;
    private int pending;

    public TenantFairVideoScheduler(
            @Qualifier("videoProcessingExecutor") ThreadPoolTaskExecutor executor,
            @Value("${app.concurrency.video.global-workers:3}") int globalLimit,
            @Value("${app.concurrency.video.per-tenant-workers:2}") int perTenantLimit,
            @Value("${app.concurrency.video.queue-capacity:500}") int queueCapacity) {
        this.executor = executor;
        this.globalLimit = Math.max(1, globalLimit);
        this.perTenantLimit = Math.max(1, Math.min(this.globalLimit, perTenantLimit));
        this.queueCapacity = Math.max(this.globalLimit * 4, queueCapacity);
        log.info("Tenant-fair video scheduler initialized: globalLimit={}, perTenantLimit={}, queueCapacity={}",
                this.globalLimit, this.perTenantLimit, this.queueCapacity);
    }

    public <T> T submitAndWait(Long tenantId, String taskKey, CheckedTask<T> work) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        boolean submitted = submit(tenantId, taskKey, () -> {
            try {
                future.complete(work.run());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        if (!submitted) {
            throw new IllegalStateException("Задача обработки видео уже выполняется: " + taskKey);
        }
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    /**
     * @return false only when this exact durable task is already queued/running.
     */
    public boolean submit(Long tenantId, String taskKey, Runnable work) {
        if (taskKey == null || taskKey.isBlank()) {
            throw new IllegalArgumentException("Video processing task key is required");
        }
        if (work == null) {
            throw new IllegalArgumentException("Video processing task is required");
        }

        long tenantKey = tenantId == null || tenantId <= 0 ? LEGACY_TENANT_KEY : tenantId;
        synchronized (monitor) {
            if (!knownTaskKeys.add(taskKey)) {
                log.debug("Duplicate heavy video task ignored: taskKey={}, tenantId={}", taskKey, tenantId);
                return false;
            }
            if (pending >= queueCapacity) {
                knownTaskKeys.remove(taskKey);
                throw new RejectedExecutionException("Очередь обработки видео переполнена");
            }

            ArrayDeque<QueuedTask> queue = queues.computeIfAbsent(tenantKey, ignored -> new ArrayDeque<>());
            queue.addLast(new QueuedTask(tenantKey, tenantId, taskKey, work));
            pending++;
            if (!rotation.contains(tenantKey)) {
                rotation.addLast(tenantKey);
            }
            logQueueState("queued", taskKey, tenantId);
            dispatchLocked();
            return true;
        }
    }

    private void dispatchLocked() {
        while (runningGlobal < globalLimit && pending > 0 && !rotation.isEmpty()) {
            QueuedTask next = pollNextEligibleLocked();
            if (next == null) {
                // Every tenant with pending work is already at its per-tenant cap.
                return;
            }

            runningGlobal++;
            runningByTenant.merge(next.tenantKey(), 1, Integer::sum);
            pending--;

            try {
                executor.execute(() -> runTask(next));
            } catch (RuntimeException rejected) {
                runningGlobal--;
                decrementTenantRunning(next.tenantKey());
                queues.computeIfAbsent(next.tenantKey(), ignored -> new ArrayDeque<>()).addFirst(next);
                pending++;
                if (!rotation.contains(next.tenantKey())) rotation.addFirst(next.tenantKey());
                log.error("Heavy video executor rejected task: taskKey={}, tenantId={}, error={}",
                        next.taskKey(), next.tenantId(), rejected.getMessage(), rejected);
                return;
            }
        }
    }

    private QueuedTask pollNextEligibleLocked() {
        int tenantsToInspect = rotation.size();
        for (int i = 0; i < tenantsToInspect; i++) {
            Long tenantKey = rotation.pollFirst();
            if (tenantKey == null) return null;

            ArrayDeque<QueuedTask> queue = queues.get(tenantKey);
            if (queue == null || queue.isEmpty()) {
                queues.remove(tenantKey);
                continue;
            }

            int tenantRunning = runningByTenant.getOrDefault(tenantKey, 0);
            if (tenantRunning >= perTenantLimit) {
                rotation.addLast(tenantKey);
                continue;
            }

            QueuedTask task = queue.pollFirst();
            if (queue.isEmpty()) {
                queues.remove(tenantKey);
            } else {
                // Round-robin: another tenant gets first chance for the next slot.
                rotation.addLast(tenantKey);
            }
            return task;
        }
        return null;
    }

    private void runTask(QueuedTask task) {
        long startedAt = System.nanoTime();
        try {
            log.info("Heavy video task started: taskKey={}, tenantId={}, thread={}",
                    task.taskKey(), task.tenantId(), Thread.currentThread().getName());
            task.work().run();
        } catch (Throwable error) {
            // Domain processors persist their own ERROR state. This final guard protects
            // scheduler capacity even if an unexpected Error escapes them.
            log.error("Unhandled heavy video task failure: taskKey={}, tenantId={}, error={}",
                    task.taskKey(), task.tenantId(), error.getMessage(), error);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            synchronized (monitor) {
                runningGlobal = Math.max(0, runningGlobal - 1);
                decrementTenantRunning(task.tenantKey());
                knownTaskKeys.remove(task.taskKey());
                log.info("Heavy video task finished: taskKey={}, tenantId={}, durationMs={}, running={}, pending={}",
                        task.taskKey(), task.tenantId(), durationMs, runningGlobal, pending);
                dispatchLocked();
            }
        }
    }

    private void decrementTenantRunning(long tenantKey) {
        int value = runningByTenant.getOrDefault(tenantKey, 0) - 1;
        if (value <= 0) runningByTenant.remove(tenantKey);
        else runningByTenant.put(tenantKey, value);
    }

    private void logQueueState(String action, String taskKey, Long tenantId) {
        log.info("Heavy video task {}: taskKey={}, tenantId={}, running={}, pending={}",
                action, taskKey, tenantId, runningGlobal, pending);
    }

    @FunctionalInterface
    public interface CheckedTask<T> {
        T run() throws Exception;
    }

    private record QueuedTask(long tenantKey, Long tenantId, String taskKey, Runnable work) {}
}
