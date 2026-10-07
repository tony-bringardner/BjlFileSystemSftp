package us.bringardner.io.filesource.sftp.client.bjl;

import java.io.IOException;
import java.nio.file.AccessDeniedException;

import us.bringardner.io.filesource.sftp.client.SftpFile;
import us.bringardner.net.ssh.sftp.SftpAttrs;
import us.bringardner.net.ssh.sftp.SftpHandle;
import us.bringardner.net.ssh.sftp.SftpInputStream;

/**
 * An open remote file. Reads that go forward use a pipelined reader (read-ahead that grows
 * as reading goes on); a read elsewhere, or a write, starts it over.
 */
class BjlSftpFile implements SftpFile {

	private final BjlSftpChannel channel;
	private final String path;
	private final SftpHandle handle;
	private final boolean writable;
	/** The read-ahead stream, or null. */
	private SftpInputStream in;

	BjlSftpFile(BjlSftpChannel channel, String path, SftpHandle handle, boolean writable) {
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
		if( in == null || in.getPosition() != position ) {
			closeReader();
			// 'false': closing the stream leaves our handle open
			in = new SftpInputStream(channel.sftp, handle, position, false);
		}
		int n;
		try {
			n = BjlSftpChannel.call(path, () -> in.read(b, off, len));
		} catch (IOException | RuntimeException e) {
			closeReader();
			throw e;
		}
		if( n < 0 ) {
			// The end of the file; ask the server again next time (it may grow)
			closeReader();
		}
		return n;
	}

	@Override
	public void write(long position, byte[] b, int off, int len) throws IOException {
		if( !writable ) {
			throw new AccessDeniedException(path, null, "opened read-only");
		}
		closeReader();   // its read-ahead may now be stale
		BjlSftpChannel.call(path, () -> {
			// Several chunks in flight, all answered before this returns
			try (us.bringardner.net.ssh.sftp.SftpOutputStream out = new us.bringardner.net.ssh.sftp.SftpOutputStream(channel.sftp, handle, position, false)) {
				out.write(b, off, len);
			}
			return null;
		});
	}

	@Override
	public void truncate(long size) throws IOException {
		if( !writable ) {
			throw new AccessDeniedException(path, null, "opened read-only");
		}
		closeReader();
		BjlSftpChannel.call(path, () -> { channel.sftp.fsetStat(handle, SftpAttrs.NONE.withSize(size)); return null; });
	}

	@Override
	public boolean isWritable() {
		return writable;
	}

	private void closeReader() throws IOException {
		if( in != null ) {
			SftpInputStream old = in;
			in = null;
			old.close();
		}
	}

	@Override
	public void close() throws IOException {
		try {
			closeReader();
		} finally {
			BjlSftpChannel.call(path, () -> { handle.close(); return null; });
		}
	}
}
