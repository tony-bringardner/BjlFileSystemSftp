package us.bringardner.io.filesource.sftp.client.bjl;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;

import us.bringardner.io.filesource.sftp.client.SftpAttributes;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpEntry;
import us.bringardner.io.filesource.sftp.client.SftpFile;
import us.bringardner.net.ssh.sftp.SftpAttrs;
import us.bringardner.net.ssh.sftp.SftpClient;
import us.bringardner.net.ssh.sftp.SftpConstants;
import us.bringardner.net.ssh.sftp.SftpDirEntry;
import us.bringardner.net.ssh.sftp.SftpException;
import us.bringardner.net.ssh.sftp.SftpHandle;

class BjlSftpChannel implements SftpChannel {

	/** How long close() waits for the server to confirm the channel is closed. */
	static final long CLOSE_WAIT_MS = 10_000;

	final SftpClient sftp;

	BjlSftpChannel(SftpClient sftp) {
		this.sftp = sftp;
	}

	interface Call<T> {
		T run() throws IOException;
	}

	/** Runs a call, turning SftpException into the types SftpChannel promises. */
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

	static SftpAttributes convert(SftpAttrs a) {
		return new SftpAttributes(a.getSize(), a.getUid(), a.getGid(), a.getPermissions(),
				(int) a.getAccessTime(), (int) a.getModifyTime());
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
			for (SftpDirEntry e : sftp.list(dir)) {
				ret.add(new SftpEntry(e.getName(), e.getLongName(), convert(e.getAttrs())));
			}
			return ret;
		});
	}

	@Override
	public String readLink(String path) throws IOException {
		return call(path, () -> sftp.readlink(path));
	}

	@Override
	public void symlink(String target, String link) throws IOException {
		call(link, () -> { sftp.symlink(target, link); return null; });
	}

	@Override
	public void hardlink(String existing, String link) throws IOException {
		call(link, () -> { sftp.hardlink(existing, link); return null; });
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

	/**
	 * posix-rename@openssh.com when the server offers it (atomic), otherwise 'to' is
	 * removed first.
	 */
	@Override
	public void replace(String from, String to) throws IOException {
		if( sftp.hasExtension(SftpConstants.EXT_POSIX_RENAME) ) {
			call(from, () -> { sftp.posixRename(from, to); return null; });
			return;
		}
		try {
			lstat(to);
			remove(to);
		} catch (NoSuchFileException e) {
			// nothing in the way
		}
		rename(from, to);
	}

	@Override
	public void chmod(String path, int mode) throws IOException {
		call(path, () -> { sftp.setStat(path, SftpAttrs.NONE.withPermissions(mode & 07777)); return null; });
	}

	@Override
	public void chown(String path, int uid) throws IOException {
		// SFTP v3 sets uid and gid together; keep the group
		call(path, () -> {
			int gid = sftp.stat(path).getGid();
			sftp.setStat(path, SftpAttrs.NONE.withOwner(uid, gid));
			return null;
		});
	}

	@Override
	public void chgrp(String path, int gid) throws IOException {
		call(path, () -> {
			int uid = sftp.stat(path).getUid();
			sftp.setStat(path, SftpAttrs.NONE.withOwner(uid, gid));
			return null;
		});
	}

	@Override
	public void setModifiedTime(String path, int seconds) throws IOException {
		// SFTP v3 sets both times together; keep the access time
		call(path, () -> {
			SftpAttrs cur = sftp.stat(path);
			sftp.setStat(path, SftpAttrs.NONE.withTimes(cur.getAccessTime(), seconds & 0xffffffffL));
			return null;
		});
	}

	@Override
	public void setAccessTime(String path, int seconds) throws IOException {
		call(path, () -> {
			SftpAttrs cur = sftp.stat(path);
			sftp.setStat(path, SftpAttrs.NONE.withTimes(seconds & 0xffffffffL, cur.getModifyTime()));
			return null;
		});
	}

	@Override
	public void truncate(String path, long size) throws IOException {
		call(path, () -> { sftp.setStat(path, SftpAttrs.NONE.withSize(size)); return null; });
	}

	@Override
	public String home() throws IOException {
		return call(".", () -> sftp.realpath("."));
	}

	/** Pipelined: several read requests in flight. */
	@Override
	public InputStream read(String path, long offset) throws IOException {
		InputStream in = call(path, () -> sftp.read(path, offset));
		return new FilterInputStream(in) {
			@Override
			public int read() throws IOException {
				return call(path, () -> in.read());
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				return call(path, () -> in.read(b, off, len));
			}

			@Override
			public long skip(long n) throws IOException {
				return call(path, () -> in.skip(n));
			}

			@Override
			public void close() throws IOException {
				call(path, () -> { in.close(); return null; });
			}
		};
	}

	/** Pipelined: several write requests in flight; errors come from a later write or close(). */
	@Override
	public OutputStream write(String path, boolean append) throws IOException {
		OutputStream out = call(path, () -> sftp.write(path, append));
		return new FilterOutputStream(out) {
			@Override
			public void write(int b) throws IOException {
				call(path, () -> { out.write(b); return null; });
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				call(path, () -> { out.write(b, off, len); return null; });
			}

			@Override
			public void flush() throws IOException {
				call(path, () -> { out.flush(); return null; });
			}

			@Override
			public void close() throws IOException {
				call(path, () -> { out.close(); return null; });
			}
		};
	}

	/** One exclusive open (SSH_FXF_EXCL), so the check and the create can't be split by another program. */
	@Override
	public boolean createNew(String path) throws IOException {
		try {
			call(path, () -> {
				sftp.open(path, SftpConstants.SSH_FXF_WRITE | SftpConstants.SSH_FXF_CREAT | SftpConstants.SSH_FXF_EXCL, SftpAttrs.NONE).close();
				return null;
			});
			return true;
		} catch (IOException e) {
			// SFTP v3 has no "already exists" status; OpenSSH answers SSH_FX_FAILURE
			try {
				lstat(path);
			} catch (IOException e2) {
				e.addSuppressed(e2);
				throw e;
			}
			return false;
		}
	}

	@Override
	public SftpFile open(String path, boolean write) throws IOException {
		int flags = write ? SftpConstants.SSH_FXF_READ | SftpConstants.SSH_FXF_WRITE : SftpConstants.SSH_FXF_READ;
		SftpHandle h = call(path, () -> sftp.open(path, flags, SftpAttrs.NONE));
		return new BjlSftpFile(this, path, h, write);
	}

	@Override
	public void close() {
		sftp.close(CLOSE_WAIT_MS);
	}
}
