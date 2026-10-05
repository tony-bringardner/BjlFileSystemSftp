package us.bringardner.io.filesource.sftp.client.jsch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import us.bringardner.io.filesource.sftp.client.SftpAttributes;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpEntry;
import us.bringardner.io.filesource.sftp.client.SftpFile;

/**
 * SFTP through JSch's public ChannelSftp API only; no copied or patched JSch
 * classes. Works with the original JSch 0.1.55 and the com.github.mwiede fork.
 */
class JschSftpChannel implements SftpChannel {

	final ChannelSftp sftp;

	JschSftpChannel(ChannelSftp sftp) {
		this.sftp = sftp;
	}

	/** An SFTP call that throws JSch's SftpException. */
	interface Call<T> {
		T run() throws SftpException;
	}

	/** Runs a call, turning SftpException into the IOException types SftpChannel promises. */
	static <T> T call(String path, Call<T> c) throws IOException {
		try {
			return c.run();
		} catch (SftpException e) {
			throw translate(path, e);
		}
	}

	static IOException translate(String path, SftpException e) {
		String msg = e.getMessage();
		switch (e.id) {
		case ChannelSftp.SSH_FX_NO_SUCH_FILE:
			return (IOException) new NoSuchFileException(path, null, msg).initCause(e);
		case ChannelSftp.SSH_FX_PERMISSION_DENIED:
			return (IOException) new AccessDeniedException(path, null, msg).initCause(e);
		default:
			return new IOException((path == null ? "" : path+": ")+e.id+": "+msg, e);
		}
	}

	static SftpAttributes convert(SftpATTRS a) {
		return new SftpAttributes(a.getSize(), a.getUId(), a.getGId(), a.getPermissions(), a.getATime(), a.getMTime());
	}

	@Override
	public boolean isOpen() {
		return sftp.isConnected();
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
	@SuppressWarnings("unchecked")   // JSch 0.1.55 returns a raw Vector
	public List<SftpEntry> list(String dir) throws IOException {
		Vector<ChannelSftp.LsEntry> ls = call(dir, () -> sftp.ls(dir));
		List<SftpEntry> ret = new ArrayList<>(ls.size());
		for (ChannelSftp.LsEntry e : ls) {
			ret.add(new SftpEntry(e.getFilename(), e.getLongname(), convert(e.getAttrs())));
		}
		return ret;
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
		call(path, () -> { sftp.rm(path); return null; });
	}

	@Override
	public void rename(String from, String to) throws IOException {
		call(from, () -> { sftp.rename(from, to); return null; });
	}

	@Override
	public void chmod(String path, int mode) throws IOException {
		call(path, () -> { sftp.chmod(mode, path); return null; });
	}

	@Override
	public void chown(String path, int uid) throws IOException {
		call(path, () -> { sftp.chown(uid, path); return null; });
	}

	@Override
	public void chgrp(String path, int gid) throws IOException {
		call(path, () -> { sftp.chgrp(gid, path); return null; });
	}

	@Override
	public void setModifiedTime(String path, int seconds) throws IOException {
		call(path, () -> { sftp.setMtime(path, seconds); return null; });
	}

	@Override
	public void setAccessTime(String path, int seconds) throws IOException {
		// SFTP v3 sets both times together; keep the modification time.
		setStat(path, a -> a.setACMODTIME(seconds, a.getMTime()));
	}

	@Override
	public void truncate(String path, long size) throws IOException {
		setStat(path, a -> a.setSIZE(size));
	}

	/** SftpATTRS.setFLAGS(int) is package-private; see setStat(). */
	private static final Method SET_FLAGS = findSetFlags();

	private static Method findSetFlags() {
		try {
			Method m = SftpATTRS.class.getDeclaredMethod("setFLAGS", int.class);
			m.setAccessible(true);
			return m;
		} catch (ReflectiveOperationException | RuntimeException e) {
			// A newer JSch without it, or a module setup that forbids access
			return null;
		}
	}

	interface Change {
		void apply(SftpATTRS a);
	}

	/**
	 * Changes some attributes with one SETSTAT. JSch offers no public way to
	 * send only the changed attributes: a SftpATTRS from stat() carries all
	 * of them (owner, permissions, times), so they'd be sent back too, and
	 * re-sending the owner can fail on a file whose group the user isn't in.
	 * So this clears the attribute flags first through the one package-private
	 * method, setFLAGS. If that isn't available it falls back to sending
	 * everything, which works in the usual case.
	 */
	void setStat(String path, Change change) throws IOException {
		call(path, () -> {
			SftpATTRS a = sftp.stat(path);
			if( SET_FLAGS != null ) {
				try {
					SET_FLAGS.invoke(a, 0);
				} catch (ReflectiveOperationException e) {
					// fall back to sending all attributes
				}
			}
			change.apply(a);
			sftp.setStat(path, a);
			return null;
		});
	}

	@Override
	public String home() throws IOException {
		return call(null, () -> sftp.getHome());
	}

	@Override
	public InputStream read(String path, long offset) throws IOException {
		return call(path, () -> sftp.get(path, null, offset));
	}

	@Override
	public OutputStream write(String path, boolean append) throws IOException {
		return call(path, () -> sftp.put(path, append ? ChannelSftp.APPEND : ChannelSftp.OVERWRITE));
	}

	/**
	 * JSch's public API can't send SFTP's exclusive-create flag, so this
	 * checks first and then opens with APPEND, which creates the file but
	 * never truncates it (CREAT without TRUNC). If another program creates
	 * the file in between, its contents are kept and this returns true.
	 */
	@Override
	public boolean createNew(String path) throws IOException {
		try {
			lstat(path);
			return false;
		} catch (NoSuchFileException e) {
			// nothing there yet
		}
		call(path, () -> sftp.put(path, ChannelSftp.APPEND)).close();
		return true;
	}

	@Override
	public SftpFile open(String path, boolean write) throws IOException {
		stat(path);   // throws NoSuchFileException if it isn't there
		return new JschSftpFile(this, path, write);
	}

	@Override
	public void close() {
		sftp.disconnect();
	}
}
