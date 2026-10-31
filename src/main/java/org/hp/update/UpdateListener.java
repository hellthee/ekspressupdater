package org.hp.update;

/**
 * Progress callbacks. Every method has a no-op default so you override only what you
 * care about. Calls arrive on the updater thread, never on the EDT: marshal with
 * SwingUtilities.invokeLater before touching Swing components.
 */
public interface UpdateListener {

    UpdateListener NOOP = new UpdateListener() {
    };

    /** Coarse stage: CHECKING, DOWNLOADING, VERIFYING, ACTIVATING, DONE. */
    default void onPhase(String phase) {
    }

    /** Cumulative byte progress for the whole update. */
    default void onProgress(long bytesDone, long bytesTotal, String currentFile) {
    }

    /** Human readable log line, useful for a details pane or a log file. */
    default void onLog(String message) {
    }

    /** A recoverable problem: a retry, a checksum mismatch, a dropped connection. */
    default void onWarning(String message) {
    }

    /** The update was abandoned. The installed version is untouched and still usable. */
    default void onFailed(String message, Throwable cause) {
    }

    /** Fans every callback out to several listeners (e.g. a log file and a dialog). */
    static UpdateListener all(final UpdateListener... targets) {
        return new UpdateListener() {
            @Override
            public void onPhase(String phase) {
                for (UpdateListener t : targets) {
                    if (t != null) {
                        t.onPhase(phase);
                    }
                }
            }

            @Override
            public void onProgress(long done, long total, String currentFile) {
                for (UpdateListener t : targets) {
                    if (t != null) {
                        t.onProgress(done, total, currentFile);
                    }
                }
            }

            @Override
            public void onLog(String message) {
                for (UpdateListener t : targets) {
                    if (t != null) {
                        t.onLog(message);
                    }
                }
            }

            @Override
            public void onWarning(String message) {
                for (UpdateListener t : targets) {
                    if (t != null) {
                        t.onWarning(message);
                    }
                }
            }

            @Override
            public void onFailed(String message, Throwable cause) {
                for (UpdateListener t : targets) {
                    if (t != null) {
                        t.onFailed(message, cause);
                    }
                }
            }
        };
    }
}
