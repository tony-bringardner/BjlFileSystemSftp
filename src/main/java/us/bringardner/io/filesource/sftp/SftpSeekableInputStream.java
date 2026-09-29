package us.bringardner.io.filesource.sftp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.ISeekableInputStream;
import us.bringardner.io.filesource.sftp.client.SftpAttributes;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpFile;

/**
 * A read-only stream that can seek, over an SFTP file. Behaves like a
 * RandomAccessFile opened with "r": reads return unsigned bytes and -1 at
 * the end, seeking past the end is allowed, a negative seek is an error.
 * <p>
 * Reads go through a buffer of the factory's chunk size, so reading forward
 * costs one request per chunk. It uses its own SFTP channel, so it doesn't
 * compete with the factory's.
 */
public class SftpSeekableInputStream implements ISeekableInputStream {

	private final SftpFileSource file;
	private final String path;
	private final SftpChannel channel;
	private final SftpFile handle;

	private final byte[] buffer;
	/** File position of buffer[0]. */
	private long bufferStart;
	/** Valid bytes in the buffer. */
	private int bufferLength;

	private long pointer;
	private long length;
	private boolean closed;

	/**
	 * Opens the file for reading.
	 *
	 * @throws FileNotFoundException if it doesn't exist, is a directory, or
	 *         can't be read (the same cases in which RandomAccessFile throws it)
	 */
	SftpSeekableInputStream(SftpFileSource file) throws IOException {
		this.file = file;
		this.path = file.getAbsolutePath();
		SftpFileSourceFactory factory = (SftpFileSourceFactory) file.getFileSourceFactory();
		this.buffer = new byte[Math.max(1024, factory.getChunkSize())];
		this.channel = factory.getConnection().openSftp();
		try {
			SftpAttributes a = channel.stat(path);   // follows links
			if( a.isDir()) {
				throw new FileNotFoundException(path+" (Is a directory)");
			}
			this.length = a.getSize();
			this.handle = channel.open(path, false);
		} catch (IOException e) {
			channel.close();
			throw SftpFileSource.openError(path, e);
		} catch (RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	private void checkOpen() throws IOException {
		if( closed ) {
			throw new IOException("Stream closed");
		}
	}

	/** True if the pointer is inside the buffered data. */
	private boolean buffered() {
		return pointer >= bufferStart && pointer < bufferStart + bufferLength;
	}

	/** Loads the buffer at the pointer; false at the end of the file. */
	private boolean fill() throws IOException {
		int n = handle.read(pointer, buffer, 0, buffer.length);
		if( n <= 0 ) {
			bufferLength = 0;
			if( pointer > length ) {
				length = pointer;
			}
			return false;
		}
		bufferStart = pointer;
		bufferLength = n;
		if( pointer + n > length ) {
			length = pointer + n;   // the file grew since it was opened
		}
		return true;
	}

	@Override
	public synchronized int read() throws IOException {
		checkOpen();
		if( !buffered() && !fill()) {
			return -1;
		}
		return buffer[(int) (pointer++ - bufferStart)] & 0xFF;
	}

	@Override
	public synchronized int read(byte[] data, int off, int len) throws IOException {
		checkOpen();
		java.util.Objects.checkFromIndexSize(off, len, data.length);
		if( len == 0 ) {
			return 0;
		}
		if( !buffered()) {
			if( len >= buffer.length ) {
				// big read: straight into the caller's array, no copy
				int n = handle.read(pointer, data, off, len);
				if( n <= 0 ) {
					return -1;
				}
				pointer += n;
				return n;
			}
			if( !fill()) {
				return -1;
			}
		}
		int n = (int) Math.min(len, bufferStart + bufferLength - pointer);
		System.arraycopy(buffer, (int) (pointer - bufferStart), data, off, n);
		pointer += n;
		return n;
	}

	@Override
	public int read(byte[] data) throws IOException {
		return read(data, 0, data.length);
	}

	@Override
	public synchronized void seek(long pos) throws IOException {
		checkOpen();
		if( pos < 0 ) {
			throw new IOException("Negative seek offset");
		}
		pointer = pos;   // the buffer is kept; it's reused if pos falls inside it
	}

	@Override
	public synchronized long getFilePointer() throws IOException {
		checkOpen();
		return pointer;
	}

	/** The file's current size on the server (one round trip). */
	@Override
	public synchronized long length() throws IOException {
		checkOpen();
		length = channel.stat(path).getSize();
		return length;
	}

	@Override
	public FileSource getFile() {
		return file;
	}

	/**
	 * An InputStream over this stream. It shares the file pointer, and
	 * closing it closes this stream.
	 */
	@Override
	public InputStream getInputStream() {
		return new InputStream() {
			@Override
			public int read() throws IOException {
				return SftpSeekableInputStream.this.read();
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				return SftpSeekableInputStream.this.read(b, off, len);
			}

			@Override
			public long skip(long n) throws IOException {
				synchronized (SftpSeekableInputStream.this) {
					checkOpen();
					if( n <= 0 ) {
						return 0;
					}
					// stop at the end of the file, like FileInputStream
					long end = Math.max(length, pointer);
					long newPos = Math.min(end, pointer + n);
					long skipped = newPos - pointer;
					pointer = newPos;
					return skipped;
				}
			}

			@Override
			public int available() throws IOException {
				synchronized (SftpSeekableInputStream.this) {
					checkOpen();
					long left = length - pointer;
					return (int) Math.max(0, Math.min(Integer.MAX_VALUE, left));
				}
			}

			@Override
			public void close() throws IOException {
				SftpSeekableInputStream.this.close();
			}
		};
	}

	@Override
	public synchronized void close() throws IOException {
		if( closed ) {
			return;
		}
		closed = true;
		try {
			handle.close();
		} finally {
			channel.close();
		}
	}
}
