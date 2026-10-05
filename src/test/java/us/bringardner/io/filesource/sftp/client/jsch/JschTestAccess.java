package us.bringardner.io.filesource.sftp.client.jsch;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import us.bringardner.io.filesource.sftp.client.SftpAttributes;
import us.bringardner.io.filesource.sftp.client.SftpChannel;

/** Reaches into the package-private JSch classes, for tests in other packages. */
public final class JschTestAccess {

	private JschTestAccess() {
	}

	/**
	 * A channel on the same JSch connection as 'jschChannel' (which must come
	 * straight from a JSch SshConnection, not the pool) that counts its own
	 * stat() calls in 'stats'.
	 */
	public static SftpChannel countingStats(SftpChannel jschChannel, AtomicInteger stats) {
		return new JschSftpChannel(((JschSftpChannel) jschChannel).sftp) {
			@Override
			public SftpAttributes stat(String path) throws IOException {
				stats.incrementAndGet();
				return super.stat(path);
			}
		};
	}
}
