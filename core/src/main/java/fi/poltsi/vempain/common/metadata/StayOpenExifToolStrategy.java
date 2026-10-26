package fi.poltsi.vempain.common.metadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.thebuzzmedia.exiftool.ExecutionStrategy;
import com.thebuzzmedia.exiftool.Version;
import com.thebuzzmedia.exiftool.process.CommandExecutor;
import com.thebuzzmedia.exiftool.process.CommandProcess;
import com.thebuzzmedia.exiftool.process.OutputHandler;
import com.thebuzzmedia.exiftool.process.command.CommandBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * One persistent {@code exiftool -stay_open True -@ -} process: every command is written as argument lines followed by {@code -execute} and the output is read
 * up to the {@code {ready}} marker. Unlike the stay-open strategy of exiftool-lib this one starts the process without a {@code -sep} option, because that
 * option would turn the list tags of the first command into joined strings instead of JSON arrays. A process that fails is closed and started again by the next
 * command; an idle process is closed after the configured timeout.
 */
@Slf4j
class StayOpenExifToolStrategy implements ExecutionStrategy {

    private static final Version STAY_OPEN_SINCE = new Version("8.36");

    private final long idleTimeoutMs;
    private final ScheduledExecutorService idleCloser;
    private CommandProcess process;
    private ScheduledFuture<?> idleClose;

    /**
     * @param idleTimeoutMs milliseconds after which an idle process is closed, 0 to keep it running
     */
    StayOpenExifToolStrategy(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
        this.idleCloser = idleTimeoutMs > 0 ? Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "exiftool-idle-close");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    @Override
    public synchronized void execute(CommandExecutor executor, String exifTool, List<String> arguments, OutputHandler handler) throws IOException {
        if (process == null
            || process.isClosed()) {
            log.debug("Starting a persistent exiftool process");
            process = executor.start(CommandBuilder.builder(exifTool, 4)
                .addArgument("-stay_open", "True")
                .addArgument("-@", "-")
                .build());
        }
        cancelIdleClose();
        try {
            var lines = new ArrayList<String>(arguments.size() + 1);
            for (var argument : arguments) {
                lines.add(argument + System.lineSeparator());
            }
            lines.add("-execute" + System.lineSeparator());
            process.write(lines);
            process.flush();
            process.read(handler);
        } catch (IOException | RuntimeException e) {
            log.warn("The persistent exiftool process failed, it is restarted by the next command: {}", e.getMessage());
            closeQuietly();
            throw e;
        } finally {
            scheduleIdleClose();
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return process != null
               && process.isRunning();
    }

    @Override
    public boolean isSupported(Version version) {
        return version.compareTo(STAY_OPEN_SINCE) >= 0;
    }

    @Override
    public synchronized void close() {
        cancelIdleClose();
        closeQuietly();
    }

    @Override
    public synchronized void shutdown() {
        close();
        if (idleCloser != null) {
            idleCloser.shutdownNow();
        }
    }

    private void scheduleIdleClose() {
        if (idleCloser != null
            && !idleCloser.isShutdown()) {
            idleClose = idleCloser.schedule(this::closeIdle, idleTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelIdleClose() {
        if (idleClose != null) {
            idleClose.cancel(false);
            idleClose = null;
        }
    }

    private synchronized void closeIdle() {
        log.debug("Closing the idle exiftool process");
        closeQuietly();
    }

    private void closeQuietly() {
        if (process == null) {
            return;
        }
        try {
            if (!process.isClosed()) {
                process.write("-stay_open" + System.lineSeparator(), "False" + System.lineSeparator());
                process.flush();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Could not ask the exiftool process to stop: {}", e.getMessage());
        }
        try {
            process.close();
        } catch (Exception e) {
            log.debug("Could not close the exiftool process: {}", e.getMessage());
        }
        process = null;
    }
}
