package com.example.meetomxh.supabase;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Api {
    public interface Callback<T> { void success(T value); void error(String message); }
    public interface Discard<T> { void discard(T value); }
    public static final ExecutorService IO = Executors.newCachedThreadPool();
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();

    public static final class Job implements Subscription {
        private volatile long epoch;
        private volatile boolean cancelled;
        private volatile Future<?> future;
        private final CopyOnWriteArrayList<okhttp3.Call> calls = new CopyOnWriteArrayList<>();

        private Job(long epoch) { this.epoch = epoch; }

        @Override public void remove() {
            cancelled = true;
            for (okhttp3.Call call : calls) call.cancel();
            Future<?> running = future;
            if (running != null) running.cancel(true);
        }
    }

    private Api() { }

    /** Rebase only the authentication job that accepted this identity transition. */
    static void sessionAccepted(long previousEpoch, long currentEpoch) {
        Job job = CURRENT.get();
        if (job != null && !job.cancelled && job.epoch == previousEpoch) job.epoch = currentEpoch;
    }

    public static void attach(okhttp3.Call call) throws java.io.IOException {
        Job job = CURRENT.get();
        if (job == null) return;
        if (job.cancelled || job.epoch != SessionManager.getInstance().generation()) {
            call.cancel();
            throw new java.io.IOException("Session changed");
        }
        job.calls.add(call);
        if (job.cancelled || job.epoch != SessionManager.getInstance().generation()) {
            job.calls.remove(call);
            call.cancel();
            throw new java.io.IOException("Session changed");
        }
    }

    public static void checkCancelled() throws java.io.IOException {
        Job job = CURRENT.get();
        if (Thread.currentThread().isInterrupted()
                || (job != null && (job.cancelled || job.epoch != SessionManager.getInstance().generation())))
            throw new java.io.IOException("Cancelled");
    }

    public static <T> Subscription run(Callable<T> task, Callback<T> callback) {
        return run(task, callback, value -> { });
    }

    /** Disposes a successful result if cancellation or a session transition suppresses its callback. */
    public static <T> Subscription run(Callable<T> task, Callback<T> callback, Discard<T> discard) {
        Job job = new Job(SessionManager.getInstance().generation());
        job.future = IO.submit(() -> {
            CURRENT.set(job);
            try {
                checkCancelled();
                T result = task.call();
                boolean posted = MAIN.post(() -> {
                    boolean[] delivered = {false};
                    boolean current = !job.cancelled && SessionManager.getInstance().runIfCurrent(job.epoch, () -> {
                        if (!job.cancelled) {
                            delivered[0] = true;
                            callback.success(result);
                        }
                    });
                    if (!current || !delivered[0]) discardSafely(discard, result);
                });
                if (!posted) discardSafely(discard, result);
            } catch (Exception error) {
                String message = ErrorMapper.message(error);
                MAIN.post(() -> {
                    if (!job.cancelled) SessionManager.getInstance().runIfCurrent(job.epoch, () -> {
                        if (!job.cancelled) callback.error(message);
                    });
                });
            } finally {
                CURRENT.remove();
            }
        });
        return job;
    }

    private static <T> void discardSafely(Discard<T> discard, T value) {
        try { discard.discard(value); } catch (RuntimeException ignored) { }
    }

    public static JSONObject obj(Object... pairs) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2)
                result.put((String) pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
            return result;
        } catch (JSONException error) {
            throw new IllegalArgumentException(error);
        }
    }

    public static JSONArray array(String... strings) {
        JSONArray result = new JSONArray();
        for (String value : strings) result.put(value);
        return result;
    }
}
