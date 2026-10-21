package app.atvos.launcher;

import android.os.Process;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One named background thread with a queue. A task that throws or runs out of memory is logged, and the next task
 * runs, so one bad app or picture cannot stop the home screen. A task that arrives after shutdown is dropped.
 */
final class Background extends ThreadPoolExecutor {

    private static final String TAG = "atvOS";

    /** {@code priority}: a {@link Process} thread priority, for example {@link Process#THREAD_PRIORITY_BACKGROUND}. */
    Background(String name, int priority) {
        super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> new Thread(() -> {
            Process.setThreadPriority(priority);
            task.run();
        }, name), new DiscardPolicy());
    }

    @Override
    public void execute(Runnable task) {
        super.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.e(TAG, "A task failed on " + Thread.currentThread().getName(), e);
            }
        });
    }
}
