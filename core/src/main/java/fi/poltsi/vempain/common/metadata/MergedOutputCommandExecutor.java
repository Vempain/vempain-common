package fi.poltsi.vempain.common.metadata;

import java.io.IOException;

import com.thebuzzmedia.exiftool.process.Command;
import com.thebuzzmedia.exiftool.process.CommandExecutor;
import com.thebuzzmedia.exiftool.process.CommandProcess;
import com.thebuzzmedia.exiftool.process.CommandResult;
import com.thebuzzmedia.exiftool.process.OutputHandler;
import com.thebuzzmedia.exiftool.process.executor.CommandExecutors;
import com.thebuzzmedia.exiftool.process.executor.DefaultCommandProcess;

/**
 * Starts exiftool with its standard error merged into its standard output. The default executor of exiftool-lib never reads the error stream of a persistent
 * process, so warnings and errors would be invisible and a chatty file could fill the pipe and block the process; merged, they arrive as lines of the command
 * output where {@link ExifToolService} reports them. One-shot commands (the version check) use the library's executor unchanged.
 */
class MergedOutputCommandExecutor implements CommandExecutor {

    private final CommandExecutor delegate = CommandExecutors.newExecutor();

    @Override
    public CommandResult execute(Command command) throws IOException {
        return delegate.execute(command);
    }

    @Override
    public CommandResult execute(Command command, OutputHandler handler) throws IOException {
        return delegate.execute(command, handler);
    }

    @Override
    public CommandProcess start(Command command) throws IOException {
        var process = new ProcessBuilder(command.getArguments()).redirectErrorStream(true)
            .start();
        return new DefaultCommandProcess(process.getInputStream(), process.getOutputStream(), process.getErrorStream());
    }
}
