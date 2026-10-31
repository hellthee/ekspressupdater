package org.hp.update;

/**
 * Reports progress as console output, for machines with no screen and for
 * {@code java -jar launcher.jar} run from a command prompt.
 */
public final class ConsoleUi implements LauncherUi {

    private long lastPercent = -1;

    @Override
    public void stage(String text) {
        System.out.println("[updater] == " + text);
    }

    @Override
    public void detail(String line) {
        System.out.println("[updater] " + line);
    }

    @Override
    public void progress(long done, long total, String currentFile) {
        if (total <= 0) {
            return;
        }
        long percent = done * 100 / total;
        if (percent / 10 != lastPercent / 10) {
            lastPercent = percent;
            System.out.println("[updater] " + percent + "%  "
                    + UpdateManager.humanBytes(done) + " of " + UpdateManager.humanBytes(total));
        }
    }

    @Override
    public void offerSkip(Runnable action) {
        // no buttons on a console; the update simply runs to completion
    }

    @Override
    public void hideSkip() {
    }

    @Override
    public void appRunning(String name) {
        System.out.println("[updater] " + name + " started successfully");
    }

    @Override
    public void failed(String headline, String detail) {
        System.out.println();
        System.out.println("---------------- PROBLEM ----------------");
        System.out.println(headline);
        if (detail != null && !detail.isEmpty()) {
            System.out.println(detail);
        }
        System.out.println("-----------------------------------------");
    }

    @Override
    public void awaitClose() {
        // nothing to wait for without a window
    }

    @Override
    public void close() {
        // nothing to close
    }
}
