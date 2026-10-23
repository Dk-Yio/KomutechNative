package tech.komutech.native_scripts.support;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;

public final class KomutechAsyncScheduler {

    private static final long TARGET_PERIOD_MS = 50L;
    private static final long MIN_PERIOD_MS = 10L;
    private static final long MAX_PERIOD_MS = 500L;
    private static final long TASK_TIMEOUT_MS = 100L;

    private static KomutechAsyncScheduler instance;
    private final ConcurrentLinkedQueue<QueuedTask> queue = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService executor;
    private Plugin plugin;
    private volatile boolean running;
    private volatile long currentPeriodMs = TARGET_PERIOD_MS;

    private record QueuedTask(Runnable asyncWork, Consumer<Plugin> syncApply) {
    }

    public static void start(Plugin plugin) {
        if (instance == null) {
            instance = new KomutechAsyncScheduler();
        }
        instance.ensureRunning(plugin);
    }

    public static void shutdown() {
        if (instance == null) {
            return;
        }
        instance.running = false;
        if (instance.executor != null) {
            instance.executor.shutdownNow();
            instance.executor = null;
        }
        instance.queue.clear();
    }

    public static void submit(Runnable asyncWork, Consumer<Plugin> syncApply) {
        if (instance == null || asyncWork == null) {
            return;
        }
        instance.queue.offer(new QueuedTask(asyncWork, syncApply == null ? ignored -> {} : syncApply));
    }

    private void ensureRunning(Plugin plugin) {
        this.plugin = plugin;
        if (running) {
            return;
        }
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "KomutechNative-AsyncScheduler");
            thread.setDaemon(true);
            return thread;
        });
        scheduleNext(0L);
    }

    private void scheduleNext(long delayMs) {
        if (!running || executor == null || executor.isShutdown()) {
            return;
        }
        executor.schedule(this::processBatch, delayMs, TimeUnit.MILLISECONDS);
    }

    private void processBatch() {
        if (!running) {
            return;
        }
        long started = System.nanoTime();
        QueuedTask task;
        while ((task = queue.poll()) != null) {
            final QueuedTask current = task;
            long taskStarted = System.nanoTime();
            try {
                current.asyncWork().run();
                Plugin activePlugin = plugin;
                if (activePlugin != null && activePlugin.isEnabled()) {
                    MainThread.run(activePlugin, () -> current.syncApply().accept(activePlugin));
                }
            } catch (Exception ignored) {
            }
            long elapsedMs = (System.nanoTime() - taskStarted) / 1_000_000L;
            if (elapsedMs > TASK_TIMEOUT_MS) {
                currentPeriodMs = Math.min(MAX_PERIOD_MS, Math.max(MIN_PERIOD_MS, elapsedMs / 2));
            }
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        long nextDelay;
        if (queue.isEmpty()) {
            nextDelay = Math.min(MAX_PERIOD_MS, currentPeriodMs * 2);
        } else {
            nextDelay = Math.max(MIN_PERIOD_MS, TARGET_PERIOD_MS - elapsedMs);
            currentPeriodMs = TARGET_PERIOD_MS;
        }
        scheduleNext(nextDelay);
    }
}
