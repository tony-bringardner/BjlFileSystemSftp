package us.bringardner.io.filesource.sftp.client.jsch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;

import com.jcraft.jsch.ChannelSftp;

import us.bringardner.io.filesource.sftp.client.SftpFile;

/**
 * Positional reads and writes with JSch's public API, which has no
 * "read/write at offset" call:
 * <ul>
 * <li>Reads use get(path, monitor, offset). The stream is kept open while
 * reads move forward and reopened after a seek (one round trip).</li>
 * <li>Writes use put(path, monitor, RESUME, offset). RESUME opens the file
 * without truncating it and starts at offset + the file's current size, so
 * passing (position - size) writes at 'position'. Each write is an open,
 * the data, and a close.</li>
 * </ul>
 * The MINA implementation does both with single positional requests.
 */
class JschSftpFile implements SftpFile {

	private final JschSftpChannel channel;
	private final ChannelSftp sftp;
	private final String path;
	private final boolean writable;

	private InputStream in;
	private long inPosition = -1;

	/**
	 * Checks access now, as a real open would: JSch has no plain "open" call,
	 * so without this a file that can't be read or written would only fail
	 * on the first read or write. The read stream opened for the check is
	 * kept for the first read; the write check opens without truncating and
	 * closes without writing, which leaves the file unchanged.
	 *
	 * @throws java.nio.file.AccessDeniedException if the file can't be opened as asked
	 */
	JschSftpFile(JschSftpChannel channel, String path, boolean writable) throws IOException {
		this.channel = channel;
		this.sftp = channel.sftp;
		this.path = path;
		this.writable = writable;
		in = JschSftpChannel.call(path, () -> sftp.get(path, null, 0L));
		inPosition = 0;
		if( writable ) {
			try {
				JschSftpChannel.call(path, () -> sftp.put(path, null, ChannelSftp.RESUME, 0L)).close();
			} catch (IOException e) {
				closeReader();
				throw e;
			}
		}
	}

	@Override
	public int read(long position, byte[] b, int off, int len) throws IOException {
		if( len == 0 ) {
			return 0;
		}
		if( in == null || position != inPosition ) {
			closeReader();
			in = JschSftpChannel.call(path, () -> sftp.get(path, null, position));
			inPosition = position;
		}
		int n = in.read(b, off, len);
		if( n > 0 ) {
			inPosition += n;
		}
		return n;
	}

	@Override
	public void write(long position, byte[] b, int off, int len) throws IOException {
		if( !writable ) {
			throw new AccessDeniedException(path, null, "opened read-only");
		}
		closeReader();   // its buffered data may now be stale
		long size;
		try {
			size = channel.stat(path).getSize();
		} catch (NoSuchFileException e) {
			size = 0;
		}
		final long offset = position - size;   // RESUME adds the size back
		try (OutputStream out = JschSftpChannel.call(path, () -> sftp.put(path, null, ChannelSftp.RESUME, offset))) {
			out.write(b, off, len);
		}
	}

	@Override
	public void truncate(long size) throws IOException {
		if( !writable ) {
			throw new AccessDeniedException(path, null, "opened read-only");
		}
		closeReader();
		channel.truncate(path, size);
	}

	@Override
	public boolean isWritable() {
		return writable;
	}

	private void closeReader() throws IOException {
		if( in != null ) {
			InputStream old = in;
			in = null;
			inPosition = -1;
			old.close();
		}
	}

	@Override
	public void close() throws IOException {
		closeReader();
	}
}
