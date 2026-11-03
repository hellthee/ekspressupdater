package org.hp.update;

/**
 * What the launcher reports while it works. There are two implementations: a window
 * ({@link LauncherWindow}) and plain console output ({@link ConsoleUi}), so the launcher
 * behaves the same whether or not there is a screen.
 */
public interface LauncherUi {

    /** Coarse step: "Checking for updates", "Starting GasX", and so on. */
    void stage(String text);

    /** A line for the details pane: what the launcher is doing, or app output. */
    void detail(String line);

    /** Byte progress of a download, or total &lt;= 0 for "no idea how long". */
    void progress(long done, long total, String currentFile);

    /** The application process is up and running. */
    void appRunning(String name);

    /**
     * The application has been started: get out of its way. The window comes back by
     * itself if {@link #failed} is called afterwards.
     */
    default void appStarted() {
    }

    /**
     * Offers the user a way to abandon a slow download and start the version they already
     * have. Running {@code action} cancels the update; what is downloaded so far is kept
     * and continues next time.
     */
    default void offerSkip(Runnable action) {
    }

    /** Takes the skip option away again. */
    default void hideSkip() {
    }

    /**
     * Something went wrong. The window stays on screen until the user closes it, so this
     * is the last thing they see rather than a process quietly disappearing.
     */
    void failed(String headline, String detail);

    /**
     * Something went wrong that the user can try again: shows the problem with Retry and
     * Close, and waits. Returns true for Retry.
     */
    default boolean askRetry(String headline, String detail) {
        failed(headline, detail);
        return false;
    }

    /** What to do if the user closes the window while work is still going on. */
    /**
     * Shows that the application could not start, with a Repair button: true when the user
     * clicks it (every file is then checked and what is damaged downloaded again). Without
     * a window there is no button, so the way to do it is printed.
     */
    default boolean askRepair(String headline, String detail) {
        failed(headline, detail + "\n\nTo repair the installation, start again with --repair.");
        return false;
    }

    default void onUserClose(Runnable action) {
    }

    /** Blocks until the user dismisses the window. Returns at once when there is none. */
    void awaitClose();

    /** Closes the window, if there is one. */
    void close();

    /** A listener that feeds update progress into this ui. */
    default UpdateListener asListener() {
        return new UpdateListener() {
            @Override
            public void onPhase(String phase) {
                if ("CHECKING".equals(phase)) {
                    stage("Checking for updates");
                } else if ("DOWNLOADING".equals(phase)) {
                    stage("Downloading update");
                } else if ("VERIFYING".equals(phase)) {
                    stage("Verifying files");
                } else if ("ACTIVATING".equals(phase)) {
                    stage("Installing update");
                }
            }

            @Override
            public void onProgress(long done, long total, String currentFile) {
                progress(done, total, currentFile);
            }

            @Override
            public void onLog(String message) {
                detail(message);
            }

            @Override
            public void onWarning(String message) {
                detail("! " + message);
            }

            @Override
            public void onFailed(String message, Throwable cause) {
                detail("FAILED " + message + (cause == null ? "" : " (" + cause + ")"));
            }
        };
    }
}
