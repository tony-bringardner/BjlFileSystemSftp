package us.bringardner.io.filesource.sftp.client;

import java.io.IOException;

/**
 * An SSH library that can open connections: JSch or Apache MINA SSHD.
 * Pick one with {@link SshProviders#get(String)}.
 */
public interface SshProvider {

	/** Short name used to select it: "jsch" or "mina". */
	String getName();

	/**
	 * Connects and authenticates.
	 *
	 * @throws IOException if the host can't be reached, the host key is
	 *         rejected, or authentication fails
	 */
	SshConnection connect(SshSettings settings) throws IOException;
}
