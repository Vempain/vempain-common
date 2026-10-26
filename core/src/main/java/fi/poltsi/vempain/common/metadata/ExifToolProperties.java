package fi.poltsi.vempain.common.metadata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Settings of the shared ExifTool runner ({@code vempain.exiftool.*}). The defaults run the {@code exiftool} found on the PATH as two persistent
 * {@code -stay_open} processes that are closed after ten idle minutes.
 */
@Data
@Component
@ConfigurationProperties(prefix = "vempain.exiftool")
public class ExifToolProperties {

    /** Path of the exiftool executable, or its name when it is on the PATH. */
    private String path = "exiftool";
    /** Whether exiftool runs as persistent {@code -stay_open} processes instead of one process per call. */
    private boolean stayOpen = true;
    /** Number of persistent processes, which is the number of exiftool commands that can run at the same time. */
    private int poolSize = 2;
    /** Milliseconds after which an idle persistent process is closed; it is started again on the next command. 0 keeps the processes forever. */
    private long idleTimeoutMs = 600_000L;
}
