package us.bringardner.io.filesource.sftp.client;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * An SFTP session on an {@link SshConnection}.
 * <p>
 * Errors: a missing file throws {@link java.nio.file.NoSuchFileException},
 * a refused permission throws {@link java.nio.file.AccessDeniedException},
 * anything else an {@link IOException}. Paths are absolute, or relative to
 * the login directory.
 * <p>
 * A channel is not meant to be used by two threads at once; open one per
 * thread, or lock around calls.
 */
public interface SftpChannel extends Closeable {

	/** True until closed or until the connection drops. */
	boolean isOpen();

	/** Attributes, following symbolic links. */
	SftpAttributes stat(String path) throws IOException;

	/** Attributes of the path itself, not following a link. */
	SftpAttributes lstat(String path) throws IOException;

	/** Directory contents, including "." and ".." if the server lists them. */
	List<SftpEntry> list(String dir) throws IOException;

	String readLink(String path) throws IOException;

	/** Creates 'link' pointing at 'target'. */
	void symlink(String target, String link) throws IOException;

	/** Creates a hard link 'link' to the existing file (OpenSSH extension). */
	void hardlink(String existing, String link) throws IOException;

	void mkdir(String path) throws IOException;

	void rmdir(String path) throws IOException;

	void remove(String path) throws IOException;

	/** Renames; fails if 'to' exists. */
	void rename(String from, String to) throws IOException;

	/**
	 * Renames 'from' to 'to', replacing 'to' if it exists. Atomic with
	 * OpenSSH's posix-rename@openssh.com extension: 'to' is the old file or
	 * the new one, never missing. Without it, 'to' is removed first, so for a
	 * moment it doesn't exist; it's never a mix of the two.
	 */
	void replace(String from, String to) throws IOException;

	/** Sets permission bits (0 to 07777), keeping the owner, times and size. */
	void chmod(String path, int mode) throws IOException;

	void chown(String path, int uid) throws IOException;

	void chgrp(String path, int gid) throws IOException;

	/** Seconds since 1970. */
	void setModifiedTime(String path, int seconds) throws IOException;

	/** Seconds since 1970. */
	void setAccessTime(String path, int seconds) throws IOException;

	/** Sets the size, cutting the file short or extending it with zeros. */
	void truncate(String path, long size) throws IOException;

	/** Absolute path of the login directory. */
	String home() throws IOException;

	/** Reads the file from 'offset' to the end. */
	InputStream read(String path, long offset) throws IOException;

	/**
	 * Writes the file, creating it if needed. Without append it's replaced;
	 * with append, data goes after the current end. Errors from the last
	 * writes are thrown by close().
	 */
	OutputStream write(String path, boolean append) throws IOException;

	/**
	 * Creates an empty file if nothing is at 'path'. An existing file (or
	 * link) is never truncated or changed, even one created a moment ago by
	 * another program.
	 *
	 * @return true if the file was created, false if something was already there
	 */
	boolean createNew(String path) throws IOException;

	/**
	 * Opens an existing file for reading and writing at any position.
	 *
	 * @param write false opens it read-only; writes then fail
	 */
	SftpFile open(String path, boolean write) throws IOException;

	@Override
	void close();
}
