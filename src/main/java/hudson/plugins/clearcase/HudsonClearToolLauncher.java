/**
 * The MIT License
 *
 * Copyright (c) 2007-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Erik Ramfelt,
 *                          Henrik Lynggaard, Peter Liljenberg, Andrew Bayer
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.clearcase;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;
import hudson.util.ForkOutputStream;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

/**
 * Class for executing the cleartool commands in the Hudson instance.
 */
public class HudsonClearToolLauncher implements ClearToolLauncher {

    private final String       executable;
    private final Launcher     launcher;
    private final TaskListener listener;

    private final String       scmName;
    private final FilePath     workspace;

    public HudsonClearToolLauncher(String executable, String scmName, TaskListener listener, FilePath workspace, Launcher launcher) {
        this.executable = executable;
        this.scmName = scmName;
        this.listener = listener;
        this.workspace = workspace;
        this.launcher = launcher;
    }

    @Override
    public String getCmdString(String[] cmd) {
        return StringUtils.join(cmd, ' ');
    }

    public Proc getLaunchedProc(String[] cmdWithExec, String[] env, InputStream inputStream, OutputStream out, FilePath path) throws IOException {
        return getLauncher().launch().cmds(cmdWithExec).envs(env).stdin(inputStream).stdout(out).pwd(path).start();
    }

    /**
     * {@inheritDoc}
     * 
     * @see hudson.plugins.clearcase.ClearToolLauncher#getLauncher()
     */
    @Override
    public Launcher getLauncher() {
        return this.launcher;
    }

    @Override
    public TaskListener getListener() {
        return listener;
    }

    @Override
    public FilePath getWorkspace() {
        return workspace;
    }

    @Override
    public boolean isUnix() {
        return launcher.isUnix();
    }

    @Override
    public boolean run(String[] cmd, FilePath filePath) throws IOException, InterruptedException {
        return run(cmd, null, null, filePath);
    }

    @Override
    public boolean run(String[] cmd, InputStream inputStream, OutputStream outputStream, FilePath filePath) throws IOException, InterruptedException {
        return run(cmd, inputStream, outputStream, filePath, false);
    }

    @Override
    public boolean run(String[] cmd, InputStream inputStream, OutputStream outputStream, FilePath filePath, boolean logCommand) throws IOException,
    InterruptedException {
        String ccVerbose = System.getenv("HUDSON_CLEARCASE_VERBOSE");
        logCommand |= StringUtils.equals("1", ccVerbose);

        OutputStream out = outputStream;
        FilePath path = filePath;
        String[] env = new String[0];

        if (path == null) {
            path = workspace;
        }

        File logFile = null;
        PrintStream logger = null;
        PrintStream fileLogger = null;
        try {
            if (logCommand) {
                logger = listener.getLogger();
            } else {
                logFile = File.createTempFile("cleartool", "log");
                fileLogger = logger = new PrintStream(logFile);
            }
            if (out == null) {
                out = logger;
            } else {
                out = new ForkOutputStream(out, logger);
            }

            String[] cmdWithExec = new String[cmd.length + 1];
            cmdWithExec[0] = executable;
            System.arraycopy(cmd, 0, cmdWithExec, 1, cmd.length);

            int r = getLaunchedProc(cmdWithExec, env, inputStream, out, path).join();
            if (logCommand) {
                listener.getLogger().println();
            }
            if (r != 0) {
                if (!logCommand) {
                    printToLogger(logFile);
                }
                listener.fatalError(scmName + " failed. exit code=" + r);
                throw new IOException("cleartool did not return the expected exit code. Command line=\"" + getCmdString(cmd) + "\", actual exit code=" + r);
            }
        } finally {
            IOUtils.closeQuietly(fileLogger);
            if (logFile != null && logFile.exists()) {
                if (!logFile.delete()) {
                    listener.error("Unable to delete" + logFile);
                }
            }
        }
        return true;
    }

    private void printToLogger(File logFile) throws FileNotFoundException, IOException {
        FileReader fileReader = new FileReader(logFile);
        BufferedReader br = new BufferedReader(fileReader);
        try {
            while (br.ready()) {
                listener.getLogger().println(br.readLine());
            }
            listener.getLogger().println();
        } finally {
            IOUtils.closeQuietly(br);
            IOUtils.closeQuietly(fileReader);
        }
    }
}