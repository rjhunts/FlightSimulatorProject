import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

public class SupervisedRunner implements Runnable {
    private static final long INITIAL_BACKOFF_MS = 100;
    private static final long MAX_BACKOFF_MS = 5000;
    private static final long SUCCESS_RESET_MS = 10_000;
    private static final long RESTART_WINDOW_MS = 30_000;
    private static final int MAX_RESTARTS_IN_WINDOW = 5;

    private final String workerName;
    private final Runnable worker;
    private final BooleanSupplier simulationRunning;
    private final Deque<Long> restartTimestamps = new ArrayDeque<>();
    private long backoffMs = INITIAL_BACKOFF_MS;

    public SupervisedRunner(String workerName, Runnable worker, BooleanSupplier simulationRunning) {
        this.workerName = workerName;
        this.worker = worker;
        this.simulationRunning = simulationRunning;
    }

    @Override
    public void run() {
        while (simulationRunning.getAsBoolean()) {
            long startTime = System.nanoTime();
            try {
                worker.run();
            } catch (Exception e) {
                if (!simulationRunning.getAsBoolean()) {
                    break;
                }
                long now = System.currentTimeMillis();
                recordRestart(now);

                if (restartBudgetExceeded(now)) {
                    logPermanentBudgetExceeded();
                    break;
                }

                logFailure(e);
                sleepBackoff();
                continue;
            }

            long runMs = Duration.ofNanos(System.nanoTime() - startTime).toMillis();
            if (!simulationRunning.getAsBoolean()) {
                break;
            }

            if (runMs >= SUCCESS_RESET_MS) {
                resetBackoff();
            }
        }
    }

    private void recordRestart(long timestampMs) {
        restartTimestamps.addLast(timestampMs);
        while (!restartTimestamps.isEmpty() && restartTimestamps.peekFirst() < timestampMs - RESTART_WINDOW_MS) {
            restartTimestamps.removeFirst();
        }
    }

    private boolean restartBudgetExceeded(long timestampMs) {
        return restartTimestamps.size() >= MAX_RESTARTS_IN_WINDOW;
    }

    private void logFailure(Exception e) {
        String message = String.format("Worker \"%s\" caught exception: %s", workerName, e.toString());
        System.err.println(message);
        Main.logToFile(message);

        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        String trace = writer.toString();
        System.err.print(trace);
        Main.logToFile(trace);
    }

    private void logPermanentBudgetExceeded() {
        String message = String.format("Worker \"%s\" exceeded restart budget; will not be restarted.", workerName);
        System.err.println(message);
        Main.logToFile(message);
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
    }

    private void resetBackoff() {
        if (backoffMs != INITIAL_BACKOFF_MS) {
            backoffMs = INITIAL_BACKOFF_MS;
            String message = String.format("Worker \"%s\" has run successfully for %dms; resetting backoff.", workerName, SUCCESS_RESET_MS);
            System.err.println(message);
            Main.logToFile(message);
        }
    }
}
