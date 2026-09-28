package us.bringardner.io.filesource.sftp.client;

/**
 * File attributes as SFTP version 3 reports them. The accessor names match
 * JSch's SftpATTRS, so code written against it reads the same.
 */
public final class SftpAttributes {

	private static final int S_IFMT = 0170000;
	private static final int S_IFDIR = 0040000;
	private static final int S_IFLNK = 0120000;
	private static final int S_IFREG = 0100000;

	private final long size;
	private final int uid;
	private final int gid;
	private final int permissions;
	private final int atime;
	private final int mtime;

	/**
	 * @param permissions full st_mode: file type bits and permission bits
	 * @param atime seconds since 1970
	 * @param mtime seconds since 1970
	 */
	public SftpAttributes(long size, int uid, int gid, int permissions, int atime, int mtime) {
		this.size = size;
		this.uid = uid;
		this.gid = gid;
		this.permissions = permissions;
		this.atime = atime;
		this.mtime = mtime;
	}

	public long getSize() {
		return size;
	}

	public int getUId() {
		return uid;
	}

	public int getGId() {
		return gid;
	}

	/** Full st_mode, including the file type bits. */
	public int getPermissions() {
		return permissions;
	}

	/** Seconds since 1970. */
	public int getATime() {
		return atime;
	}

	/** Seconds since 1970. */
	public int getMTime() {
		return mtime;
	}

	public boolean isDir() {
		return (permissions & S_IFMT) == S_IFDIR;
	}

	public boolean isLink() {
		return (permissions & S_IFMT) == S_IFLNK;
	}

	public boolean isReg() {
		return (permissions & S_IFMT) == S_IFREG;
	}

	/** Like ls -l: "drwxr-xr-x", with s/S and t/T for the special bits. */
	public String getPermissionsString() {
		StringBuilder sb = new StringBuilder(10);
		sb.append(isDir() ? 'd' : isLink() ? 'l' : '-');
		sb.append((permissions & 0400) != 0 ? 'r' : '-');
		sb.append((permissions & 0200) != 0 ? 'w' : '-');
		sb.append(exec(permissions & 0100, permissions & 04000, 's'));
		sb.append((permissions & 0040) != 0 ? 'r' : '-');
		sb.append((permissions & 0020) != 0 ? 'w' : '-');
		sb.append(exec(permissions & 0010, permissions & 02000, 's'));
		sb.append((permissions & 0004) != 0 ? 'r' : '-');
		sb.append((permissions & 0002) != 0 ? 'w' : '-');
		sb.append(exec(permissions & 0001, permissions & 01000, 't'));
		return sb.toString();
	}

	private static char exec(int x, int special, char letter) {
		if( special != 0 ) {
			return x != 0 ? letter : Character.toUpperCase(letter);
		}
		return x != 0 ? 'x' : '-';
	}

	@Override
	public String toString() {
		return getPermissionsString()+" "+uid+" "+gid+" "+size+" "+mtime;
	}
}
