package us.bringardner.io.filesource.sftp.client.mina;

import java.io.IOException;
import java.nio.file.AccessDeniedException;

import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.client.SftpClient.CloseableHandle;

import us.bringardner.io.filesource.sftp.client.SftpFile;

/** Positional reads and writes on an open MINA handle: one request each. */
class MinaSftpFile implements SftpFile {

	private final MinaSftpChannel channel;
	private final String path;
	private final CloseableHandle handle;
	private final boolean writable;

	MinaSftpFile(MinaSftpChannel channel, String path, CloseableHandle handle, boolean writable) {
		this.channel = channel;
		this.path = path;
		this.handle = handle;
		this.writable = writable;
	}

	@Override
	public int read(long position, byte[] b, int off, int len) throws IOException {
		if( len == 0 ) {
			return 0;
		}
		return MinaSftpChannel.call(path, () -> channel.sftp.read(handle, position, b, off, len));
	}

	@Override
	public void write(long position, byte[] b, int off, int len) throws IOException {
		if( !writable ) {
			throw new AccessDeniedException(path, null, "opened read-only");
		}
		MinaSftpChannel.call(path, () -> { channel.sftp.write(handle, position, b, off, len); return null; });
	}

	@Override
	public void truncate(long size) throws IOException {
		if( !writable ) {
			throw new AccessDeniedException(path, null, "opened read-only");
		}
		MinaSftpChannel.call(path, () -> { channel.sftp.setStat(handle, new Attributes().size(size)); return null; });
	}

	@Override
	public boolean isWritable() {
		return writable;
	}

	@Override
	public void close() throws IOException {
		handle.close();
	}
}
