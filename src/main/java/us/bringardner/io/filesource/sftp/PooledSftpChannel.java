package us.bringardner.io.filesource.sftp;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.List;

import us.bringardner.io.filesource.sftp.client.SftpAttributes;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpEntry;
import us.bringardner.io.filesource.sftp.client.SftpFile;

/**
 * A channel borrowed from an {@link SftpChannelPool}. close() gives it back
 * to the pool, but only if it's still open, nothing on it failed, and every
 * stream and file opened through it has been closed. Otherwise close()
 * closes the channel, since it may hold unanswered requests or an open handle.
 * <p>
 * What counts as failed: any exception from a stream or file opened through
 * it, and any exception from a call except NoSuchFileException and
 * AccessDeniedException. Those two are the server's answer to the request,
 * so nothing is left pending; and they're common (exists() on a missing
 * file), so closing the channel for them would cost a new channel each time.
 * After close() this object can't be used, since the channel may already
 * belong to someone else.
 */
class PooledSftpChannel implements SftpChannel {

	private final SftpChannelPool pool;
	private final SftpChannel channel;
	private final SftpChannelPool.Use use;
	/** Streams and files opened through this and not closed yet. */
	private int openHandles;
	private boolean failed;
	private boolean closed;

	PooledSftpChannel(SftpChannelPool pool, SftpChannel channel, SftpChannelPool.Use use) {
		this.pool = pool;
		this.channel = channel;
		this.use = use;
	}

	private interface Call<T> {
		T run() throws IOException;
	}

	private <T> T call(Call<T> c) throws IOException {
		synchronized (this) {
			if( closed ) {
				throw new IOException("SFTP channel closed");
			}
		}
		try {
			return c.run();
		} catch (NoSuchFileException | AccessDeniedException e) {
			throw e;   // the server's answer; the channel is fine (see the class comment)
		} catch (IOException | RuntimeException e) {
			fail();
			throw e;
		}
	}

	private synchronized void fail() {
		failed = true;
	}

	private synchronized void opened() {
		openHandles++;
	}

	private synchronized void closedHandle() {
		openHandles--;
	}

	@Override
	public synchronized boolean isOpen() {
		return !closed && channel.isOpen();
	}

	@Override
	public SftpAttributes stat(String path) throws IOException {
		return call(() -> channel.stat(path));
	}

	@Override
	public SftpAttributes lstat(String path) throws IOException {
		return call(() -> channel.lstat(path));
	}

	@Override
	public List<SftpEntry> list(String dir) throws IOException {
		return call(() -> channel.list(dir));
	}

	@Override
	public String readLink(String path) throws IOException {
		return call(() -> channel.readLink(path));
	}

	@Override
	public void symlink(String target, String link) throws IOException {
		call(() -> { channel.symlink(target, link); return null; });
	}

	@Override
	public void hardlink(String existing, String link) throws IOException {
		call(() -> { channel.hardlink(existing, link); return null; });
	}

	@Override
	public void mkdir(String path) throws IOException {
		call(() -> { channel.mkdir(path); return null; });
	}

	@Override
	public void rmdir(String path) throws IOException {
		call(() -> { channel.rmdir(path); return null; });
	}

	@Override
	public void remove(String path) throws IOException {
		call(() -> { channel.remove(path); return null; });
	}

	@Override
	public void rename(String from, String to) throws IOException {
		call(() -> { channel.rename(from, to); return null; });
	}

	@Override
	public void replace(String from, String to) throws IOException {
		call(() -> { channel.replace(from, to); return null; });
	}

	@Override
	public void chmod(String path, int mode) throws IOException {
		call(() -> { channel.chmod(path, mode); return null; });
	}

	@Override
	public void chown(String path, int uid) throws IOException {
		call(() -> { channel.chown(path, uid); return null; });
	}

	@Override
	public void chgrp(String path, int gid) throws IOException {
		call(() -> { channel.chgrp(path, gid); return null; });
	}

	@Override
	public void setModifiedTime(String path, int seconds) throws IOException {
		call(() -> { channel.setModifiedTime(path, seconds); return null; });
	}

	@Override
	public void setAccessTime(String path, int seconds) throws IOException {
		call(() -> { channel.setAccessTime(path, seconds); return null; });
	}

	@Override
	public void truncate(String path, long size) throws IOException {
		call(() -> { channel.truncate(path, size); return null; });
	}

	@Override
	public String home() throws IOException {
		return call(channel::home);
	}

	@Override
	public InputStream read(String path, long offset) throws IOException {
		InputStream in = call(() -> channel.read(path, offset));
		opened();
		return new TrackedInputStream(in);
	}

	@Override
	public OutputStream write(String path, boolean append) throws IOException {
		OutputStream out = call(() -> channel.write(path, append));
		opened();
		return new TrackedOutputStream(out);
	}

	@Override
	public boolean createNew(String path) throws IOException {
		return call(() -> channel.createNew(path));
	}

	@Override
	public SftpFile open(String path, boolean write) throws IOException {
		SftpFile f = call(() -> channel.open(path, write));
		opened();
		return new TrackedFile(f);
	}

	/** Gives the channel back to the pool, or closes it; see the class comment. */
	@Override
	public void close() {
		boolean reusable;
		synchronized (this) {
			if( closed ) {
				return;
			}
			closed = true;
			reusable = !failed && openHandles == 0;
		}
		pool.giveBack(channel, reusable, use);
	}

	/** Counts the stream as closed once, and marks the channel failed if it throws. */
	private class TrackedInputStream extends FilterInputStream {
		private boolean done;

		TrackedInputStream(InputStream in) {
			super(in);
		}

		@Override
		public int read() throws IOException {
			try {
				return super.read();
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			}
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			try {
				return in.read(b, off, len);
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			}
		}

		@Override
		public long skip(long n) throws IOException {
			try {
				return super.skip(n);
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			}
		}

		@Override
		public void close() throws IOException {
			if( done ) {
				return;
			}
			done = true;
			try {
				super.close();
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			} finally {
				closedHandle();
			}
		}
	}

	private class TrackedOutputStream extends FilterOutputStream {
		private boolean done;

		TrackedOutputStream(OutputStream out) {
			super(out);
		}

		@Override
		public void write(int b) throws IOException {
			try {
				out.write(b);
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			try {
				out.write(b, off, len);   // FilterOutputStream would write byte by byte
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			}
		}

		@Override
		public void flush() throws IOException {
			try {
				out.flush();
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			}
		}

		@Override
		public void close() throws IOException {
			if( done ) {
				return;
			}
			done = true;
			try {
				out.close();   // sends the last data and checks the replies
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			} finally {
				closedHandle();
			}
		}
	}

	private class TrackedFile implements SftpFile {
		private final SftpFile file;
		private boolean done;

		TrackedFile(SftpFile file) {
			this.file = file;
		}

		@Override
		public int read(long position, byte[] b, int off, int len) throws IOException {
			try {
				return file.read(position, b, off, len);
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			}
		}

		@Override
		public void write(long position, byte[] b, int off, int len) throws IOException {
			try {
				file.write(position, b, off, len);
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			}
		}

		@Override
		public void truncate(long size) throws IOException {
			try {
				file.truncate(size);
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			}
		}

		@Override
		public boolean isWritable() {
			return file.isWritable();
		}

		@Override
		public void close() throws IOException {
			if( done ) {
				return;
			}
			done = true;
			try {
				file.close();
			} catch (IOException | RuntimeException e) {
				fail();
				throw e;
			} finally {
				closedHandle();
			}
		}
	}
}
