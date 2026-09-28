package us.bringardner.io.filesource.sftp.client;

import java.io.Closeable;
import java.io.IOException;

/** An open remote file, read and written at explicit positions. */
public interface SftpFile extends Closeable {

	/**
	 * Reads up to 'len' bytes at 'position'. May return fewer bytes than
	 * asked for even before the end of the file.
	 *
	 * @return bytes read, or -1 at the end of the file
	 */
	int read(long position, byte[] b, int off, int len) throws IOException;

	/** Writes all 'len' bytes at 'position', extending the file if needed. */
	void write(long position, byte[] b, int off, int len) throws IOException;

	/** Sets the file's size. */
	void truncate(long size) throws IOException;

	/** False if it was opened read-only. */
	boolean isWritable();

	@Override
	void close() throws IOException;
}
