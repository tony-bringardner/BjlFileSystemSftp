package us.bringardner.io.filesource.sftp.client;

import java.io.Closeable;
import java.io.IOException;

/**
 * One authenticated SSH connection. It is safe to use from several threads:
 * each {@link SftpChannel} it opens is independent, and each thread should
 * use its own channel.
 */
public interface SshConnection extends Closeable {

	/** True while the connection is up. */
	boolean isConnected();

	/** Opens a new SFTP channel on this connection. Close it when done. */
	SftpChannel openSftp() throws IOException;

	/**
	 * Runs a command on the server (needs shell access) and waits for it.
	 *
	 * @param timeoutMs how long to wait for the command to finish
	 * @throws IOException if it can't run or doesn't finish in time
	 */
	ExecResult exec(String command, long timeoutMs) throws IOException;

	/** Disconnects. Channels opened on it stop working. */
	@Override
	void close();

	/** Result of {@link SshConnection#exec}. */
	final class ExecResult {
		public final int exitStatus;
		public final String stdout;
		public final String stderr;

		public ExecResult(int exitStatus, String stdout, String stderr) {
			this.exitStatus = exitStatus;
			this.stdout = stdout;
			this.stderr = stderr;
		}
	}
}
