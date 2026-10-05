package us.bringardner.io.filesource.sftp.client.mina;

import java.io.IOException;
import java.nio.file.AccessDeniedException;

import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.client.SftpClient.CloseableHandle;
import org.apache.sshd.sftp.client.impl.AbstractSftpClient;
import org.apache.sshd.sftp.client.impl.SftpInputStreamAsync;

import us.bringardner.io.IoUtils;
import us.bringardner.io.filesource.sftp.client.SftpFile;

/**
 * Positional reads and writes on an open MINA handle.
 * <p>
 * A read after a seek is one request, answered before the next is sent.
 * Once reads move forward (each starts where the last one ended), they come
 * from MINA's pipelined reader on the same handle instead, which keeps
 * several requests in flight, so a sequential pass doesn't wait one round
 * trip per chunk. The reader asks for at most READ_AHEAD_START bytes; each
 * time those are used up it's reopened with twice as many, up to
 * READ_AHEAD_MAX. That bounds the data thrown away when it's closed early.
 * <p>
 * The reader is closed on a seek, a write, a truncate (its data may be out
 * of date), at the end of the file (so a file that grows is seen), and on
 * close. Writes and truncates are one request each, and the read after one
 * is a single request again, so reading and writing in turn doesn't fetch
 * data ahead only to throw it away.
 */
class MinaSftpFile implements SftpFile {

	/** Read-ahead when reads start moving forward: 4 requests. */
	static final int READ_AHEAD_START = 4 * MinaSftpChannel.BUFFER_SIZE;
	/** Most read-ahead: 64 requests, 2 MB, the size of MINA's default channel window. */
	static final int READ_AHEAD_MAX = 64 * MinaSftpChannel.BUFFER_SIZE;

	private final MinaSftpChannel channel;
	private final String path;
	private final CloseableHandle handle;
	private final boolean writable;

	/** The pipelined reader, or null. */
	private SftpInputStreamAsync in;
	/** File position of the next byte 'in' returns. */
	private long inPosition;
	/** 'in' is used for nothing at or past this position. */
	private long inEnd;
	/** Where the last read ended, or -1 if the next read can't continue it. */
	private long nextPosition = -1;
	private int readAhead = READ_AHEAD_START;

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
		if( position != nextPosition ) {
			closeReader();   // a seek: start over
			readAhead = READ_AHEAD_START;
		} else if( in != null && inPosition >= inEnd ) {
			closeReader();   // used up and still going forward: read further ahead
			readAhead = Math.min(2 * readAhead, READ_AHEAD_MAX);
		}
		if( in == null && position == nextPosition && channel.sftp instanceof AbstractSftpClient ) {
			// the size hint (inEnd - 1) stops the reader asking for data past inEnd;
			// 'false': closing it leaves our handle open
			inEnd = position + readAhead;
			in = new SftpInputStreamAsync((AbstractSftpClient) channel.sftp, MinaSftpChannel.BUFFER_SIZE,
					position, inEnd - 1, path, handle, false);
			inPosition = position;
		}
		int n;
		if( in != null ) {
			int want = (int) Math.min(len, inEnd - inPosition);
			try {
				n = MinaSftpChannel.call(path, () -> in.read(b, off, want));
			} catch (IOException | RuntimeException e) {
				IoUtils.closeQuietly(in);
				in = null;
				nextPosition = -1;
				throw e;
			}
			if( n > 0 ) {
				inPosition += n;
			} else {
				closeReader();   // the end of the file; ask the server again next time
			}
		} else {
			n = MinaSftpChannel.call(path, () -> channel.sftp.read(handle, position, b, off, len));
		}
		nextPosition = n > 0 ? position + n : -1;
		return n;
	}

	@Override
	public void write(long position, byte[] b, int off, int len) throws IOException {
		if( !writable ) {
			throw new AccessDeniedException(path, null, "opened read-only");
		}
		closeReader();   // its read-ahead may now be stale
		nextPosition = -1;
		MinaSftpChannel.call(path, () -> { channel.sftp.write(handle, position, b, off, len); return null; });
	}

	@Override
	public void truncate(long size) throws IOException {
		if( !writable ) {
			throw new AccessDeniedException(path, null, "opened read-only");
		}
		closeReader();
		nextPosition = -1;
		MinaSftpChannel.call(path, () -> { channel.sftp.setStat(handle, new Attributes().size(size)); return null; });
	}

	@Override
	public boolean isWritable() {
		return writable;
	}

	/**
	 * Closes the pipelined reader, waiting for the answers to the requests it
	 * still has in flight.
	 */
	private void closeReader() throws IOException {
		if( in != null ) {
			SftpInputStreamAsync old = in;
			in = null;
			MinaSftpChannel.call(path, () -> { old.close(); return null; });
		}
	}

	@Override
	public void close() throws IOException {
		try {
			closeReader();
		} finally {
			handle.close();
		}
	}
}
