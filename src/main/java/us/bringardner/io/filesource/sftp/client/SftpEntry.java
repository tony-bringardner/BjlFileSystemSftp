package us.bringardner.io.filesource.sftp.client;

/** One entry of a directory listing. */
public final class SftpEntry {

	private final String name;
	private final String longName;
	private final SftpAttributes attributes;

	/**
	 * @param longName the server's ls -l style line, which carries the owner
	 *        and group names (SFTP version 3 sends only numeric ids)
	 */
	public SftpEntry(String name, String longName, SftpAttributes attributes) {
		this.name = name;
		this.longName = longName;
		this.attributes = attributes;
	}

	public String getFilename() {
		return name;
	}

	public String getLongname() {
		return longName;
	}

	/** Attributes of the entry itself; a link is not followed. */
	public SftpAttributes getAttrs() {
		return attributes;
	}

	@Override
	public String toString() {
		return longName != null ? longName : name;
	}
}
