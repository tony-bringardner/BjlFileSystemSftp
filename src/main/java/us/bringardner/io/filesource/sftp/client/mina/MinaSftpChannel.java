package us.bringardner.io.filesource.sftp.client.mina;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.client.SftpClient.CloseableHandle;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;
import org.apache.sshd.sftp.client.SftpClient.OpenMode;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import us.bringardner.io.filesource.sftp.client.SftpAttributes;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpEntry;
import us.bringardner.io.filesource.sftp.client.SftpFile;

/** SFTP through Apache MINA SSHD's SftpClient. */
class MinaSftpChannel implements SftpChannel {

	/** Bytes per request for streams. */
	static final int BUFFER_SIZE = 32 * 1024;

	final SftpClient sftp;

	MinaSftpChannel(SftpClient sftp) {
		this.sftp = sftp;
	}

	interface Call<T> {
		T run() throws IOException;
	}

	/** Runs a call, turning MINA's SftpException into the types SftpChannel promises. */
	static <T> T call(String path, Call<T> c) throws IOException {
		try {
			return c.run();
		} catch (SftpException e) {
			throw translate(path, e);
		}
	}

	static IOException translate(String path, SftpException e) {
		switch (e.getStatus()) {
		case SftpConstants.SSH_FX_NO_SUCH_FILE:
			return (IOException) new NoSuchFileException(path, null, e.getMessage()).initCause(e);
		case SftpConstants.SSH_FX_PERMISSION_DENIED:
			return (IOException) new AccessDeniedException(path, null, e.getMessage()).initCause(e);
		default:
			return e;
		}
	}

	static int seconds(FileTime t) {
		return t == null ? 0 : (int) t.to(TimeUnit.SECONDS);
	}

	static SftpAttributes convert(Attributes a) {
		return new SftpAttributes(a.getSize(), a.getUserId(), a.getGroupId(), a.getPermissions(),
				seconds(a.getAccessTime()), seconds(a.getModifyTime()));
	}

	@Override
	public boolean isOpen() {
		return sftp.isOpen();
	}

	@Override
	public SftpAttributes stat(String path) throws IOException {
		return call(path, () -> convert(sftp.stat(path)));
	}

	@Override
	public SftpAttributes lstat(String path) throws IOException {
		return call(path, () -> convert(sftp.lstat(path)));
	}

	@Override
	public List<SftpEntry> list(String dir) throws IOException {
		return call(dir, () -> {
			List<SftpEntry> ret = new ArrayList<>();
			for (DirEntry e : sftp.readDir(dir)) {
				ret.add(new SftpEntry(e.getFilename(), e.getLongFilename(), convert(e.getAttributes())));
			}
			return ret;
		});
	}

	@Override
	public String readLink(String path) throws IOException {
		return call(path, () -> sftp.readLink(path));
	}

	@Override
	public void symlink(String target, String link) throws IOException {
		call(link, () -> { sftp.symLink(link, target); return null; });
	}

	@Override
	public void hardlink(String existing, String link) throws IOException {
		call(link, () -> {
			if( OpenSshHardLink.isAvailable(sftp)) {
				new OpenSshHardLink(sftp).link(existing, link);   // OpenSSH, SFTP v3
			} else {
				sftp.link(link, existing, false);                 // SFTP v6 servers
			}
			return null;
		});
	}

	@Override
	public void mkdir(String path) throws IOException {
		call(path, () -> { sftp.mkdir(path); return null; });
	}

	@Override
	public void rmdir(String path) throws IOException {
		call(path, () -> { sftp.rmdir(path); return null; });
	}

	@Override
	public void remove(String path) throws IOException {
		call(path, () -> { sftp.remove(path); return null; });
	}

	@Override
	public void rename(String from, String to) throws IOException {
		call(from, () -> { sftp.rename(from, to); return null; });
	}

	@Override
	public void chmod(String path, int mode) throws IOException {
		call(path, () -> { sftp.setStat(path, new Attributes().perms(mode & 07777)); return null; });
	}

	@Override
	public void chown(String path, int uid) throws IOException {
		// SFTP v3 sets uid and gid together; keep the group
		call(path, () -> {
			int gid = sftp.stat(path).getGroupId();
			sftp.setStat(path, new Attributes().owner(uid, gid));
			return null;
		});
	}

	@Override
	public void chgrp(String path, int gid) throws IOException {
		call(path, () -> {
			int uid = sftp.stat(path).getUserId();
			sftp.setStat(path, new Attributes().owner(uid, gid));
			return null;
		});
	}

	@Override
	public void setModifiedTime(String path, int seconds) throws IOException {
		// SFTP v3 sets both times together; keep the access time
		call(path, () -> {
			Attributes cur = sftp.stat(path);
			sftp.setStat(path, new Attributes()
					.accessTime(cur.getAccessTime())
					.modifyTime(FileTime.from(seconds, TimeUnit.SECONDS)));
			return null;
		});
	}

	@Override
	public void setAccessTime(String path, int seconds) throws IOException {
		call(path, () -> {
			Attributes cur = sftp.stat(path);
			sftp.setStat(path, new Attributes()
					.accessTime(FileTime.from(seconds, TimeUnit.SECONDS))
					.modifyTime(cur.getModifyTime()));
			return null;
		});
	}

	@Override
	public void truncate(String path, long size) throws IOException {
		call(path, () -> { sftp.setStat(path, new Attributes().size(size)); return null; });
	}

	@Override
	public String home() throws IOException {
		return call(".", () -> sftp.canonicalPath("."));
	}

	@Override
	public InputStream read(String path, long offset) throws IOException {
		CloseableHandle h = call(path, () -> sftp.open(path, EnumSet.of(OpenMode.Read)));
		return new HandleInputStream(this, path, h, offset);
	}

	@Override
	public OutputStream write(String path, boolean append) throws IOException {
		return call(path, () -> sftp.write(path, BUFFER_SIZE,
				append
				? EnumSet.of(OpenMode.Write, OpenMode.Create, OpenMode.Append)
				: EnumSet.of(OpenMode.Write, OpenMode.Create, OpenMode.Truncate)));
	}

	@Override
	public SftpFile open(String path, boolean write) throws IOException {
		CloseableHandle h = call(path, () -> sftp.open(path,
				write ? EnumSet.of(OpenMode.Read, OpenMode.Write) : EnumSet.of(OpenMode.Read)));
		return new MinaSftpFile(this, path, h, write);
	}

	@Override
	public void close() {
		try {
			sftp.close();
		} catch (IOException e) {
			// closing; nothing useful to do
		}
	}

	/** Reads a file from a position through an open handle, BUFFER_SIZE bytes per request. */
	static class HandleInputStream extends InputStream {
		private final MinaSftpChannel channel;
		private final String path;
		private final CloseableHandle handle;
		private final byte[] buffer = new byte[BUFFER_SIZE];
		private long position;
		private int pos;
		private int count;
		private boolean eof;
		private boolean closed;

		HandleInputStream(MinaSftpChannel channel, String path, CloseableHandle handle, long position) {
			this.channel = channel;
			this.path = path;
			this.handle = handle;
			this.position = position;
		}

		private boolean fill() throws IOException {
			if( closed ) {
				throw new IOException("Stream closed");
			}
			if( eof ) {
				return false;
			}
			int n = call(path, () -> channel.sftp.read(handle, position, buffer, 0, buffer.length));
			if( n < 0 ) {
				eof = true;
				return false;
			}
			position += n;
			pos = 0;
			count = n;
			return true;
		}

		@Override
		public int read() throws IOException {
			if( pos >= count && !fill()) {
				return -1;
			}
			return buffer[pos++] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			java.util.Objects.checkFromIndexSize(off, len, b.length);
			if( len == 0 ) {
				return 0;
			}
			if( pos >= count && !fill()) {
				return -1;
			}
			int n = Math.min(len, count - pos);
			System.arraycopy(buffer, pos, b, off, n);
			pos += n;
			return n;
		}

		@Override
		public long skip(long n) throws IOException {
			if( n <= 0 ) {
				return 0;
			}
			long inBuffer = Math.min(n, count - pos);
			pos += (int) inBuffer;
			long rest = n - inBuffer;
			position += rest;   // just move the read position; no data transferred
			return n;
		}

		@Override
		public int available() {
			return count - pos;
		}

		@Override
		public void close() throws IOException {
			if( !closed ) {
				closed = true;
				handle.close();
			}
		}
	}
}
