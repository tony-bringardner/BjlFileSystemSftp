/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 * ~version~
 */


package us.bringardner.io.filesource.sftp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;

import us.bringardner.io.filesource.AbstractRandomAccessIoController;
import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.sftp.client.SftpAttributes;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpFile;

/**
 * Random access to an SFTP file in chunks of the factory's chunk size. Uses
 * a channel of its own from the pool. When it can write and the factory uses
 * JSch, the channel comes from a MINA connection to the same account (see
 * SftpFileSourceFactory.randomAccessFactory()).
 */
public class SftpRandomAccessIoController extends AbstractRandomAccessIoController {

	private final SftpFileSourceFactory myFactory;
	private final SftpChannel channel;
	private SftpFile handle;
	/** Opened with mode "r": the handle is read-only. */
	private final boolean readOnly;


	/**
	 * Opens the file for reading and writing, lazily: it's created on first
	 * use if missing, and opened read-only if it can't be written (writes
	 * then fail). Kept for existing callers; FileSource.getRandomAccessStream
	 * uses the mode constructor instead.
	 */
	public SftpRandomAccessIoController(FileSource file) throws IOException {
		super(file);
		myFactory = (SftpFileSourceFactory) file.getFileSourceFactory();
		channel = myFactory.randomAccessFactory().openSftp();   // it may write: see randomAccessFactory()
		readOnly = false;
	}

	/**
	 * Opens the file now, in one of RandomAccessFile's modes. "r" opens it
	 * read-only; "rw", "rws" and "rwd" open it for reading and writing and
	 * create it if it doesn't exist. (Every write is sent to the server when
	 * the chunk is saved, so "rws"/"rwd" need nothing extra.)
	 *
	 * @throws IllegalArgumentException for any other mode
	 * @throws FileNotFoundException if it's a directory, missing in "r"
	 *         mode, or can't be opened with the requested access
	 */
	public SftpRandomAccessIoController(FileSource file, String mode) throws IOException {
		super(file);
		if( !("r".equals(mode) || "rw".equals(mode) || "rws".equals(mode) || "rwd".equals(mode))) {
			throw new IllegalArgumentException("Illegal mode \""+mode+"\" must be one of \"r\", \"rw\", \"rws\", or \"rwd\"");
		}
		myFactory = (SftpFileSourceFactory) file.getFileSourceFactory();
		readOnly = mode.equals("r");
		// Writing goes through MINA even when the factory uses JSch, which
		// can't write at an offset safely; see randomAccessFactory().
		channel = readOnly ? myFactory.openSftp() : myFactory.randomAccessFactory().openSftp();
		String path = file.getAbsolutePath();
		try {
			SftpAttributes a;
			try {
				a = channel.stat(path);   // follows links
			} catch (NoSuchFileException e) {
				if( readOnly ) {
					throw e;
				}
				channel.createNew(path);   // create it, like RandomAccessFile "rw"; never truncates
				fileChanged();
				a = channel.stat(path);
			}
			if( a.isDir()) {
				throw new FileNotFoundException(path+" (Is a directory)");
			}
			handle = channel.open(path, !readOnly);
		} catch (IOException e) {
			channel.close();
			throw SftpFileSource.openError(path, e);
		} catch (RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	@Override
	protected Chunk readChunkForPos(long pos) throws IOException {
		Chunk ret = new Chunk();
		long len = length();
		int chunkSize = myFactory.getChunkSize();
		if(len == 0 || pos >= len) {
			ret.size = 0;
			ret.data = new byte[chunkSize];
			ret.start = len;
			ret.isNew = true;
		} else {
			long chunk = pos/chunkSize;
			ret.start = chunk * chunkSize;
			ret.data = readFully(ret.start, (int)Math.min(chunkSize, len-ret.start));
			ret.size = ret.data.length;
		}
		return ret;
	}

	/**
	 * Reads 'want' bytes starting at 'start'. A server may return fewer bytes
	 * than asked for, so keep asking until we have them all or reach EOF.
	 */
	private byte[] readFully(long start, int want) throws IOException {
		byte[] ret = new byte[want];
		int got = 0;
		while( got < want ) {
			int n = getHandle().read(start+got, ret, got, want-got);
			if( n <= 0 ) {
				break; // EOF
			}
			got += n;
		}
		if( got == 0 ) {
			throw new IOException("Unexpected end of file at "+start+" (length was "+length()
					+"); was "+file.getAbsolutePath()+" changed while it was open?");
		}
		return got == want ? ret : Arrays.copyOf(ret, got);
	}

	@Override
	protected void writeChunk(Chunk chunk) throws IOException {
		if( readOnly ) {
			throw new IOException("Opened read-only (mode \"r\")");
		}
		getHandle().write(chunk.start, chunk.data, 0, chunk.data.length);
		fileChanged();

	}

	@Override
	public void setLength0(long newLength) throws IOException {
		if( readOnly ) {
			throw new IOException("Opened read-only (mode \"r\")");
		}
		long len = length();
		if( len != newLength) {
			if( newLength< length()) {
				// SFTP SETSTAT with only the size; no shell command
				getHandle().truncate(newLength);
			} else {
				// write the last byte; the gap reads as zeros
				getHandle().write(newLength-1, new byte[1], 0, 1);
			}
			fileChanged();
		}
	}

	/** The file's size or times changed, so its cached attributes are stale. */
	private void fileChanged() {
		if (file instanceof SftpFileSource) {
			((SftpFileSource) file).clearAttr();
		}
	}

	private SftpFile getHandle() throws IOException {
		if( handle == null ) {
			// only the one-argument constructor gets here; the mode constructor opens eagerly
			try {
				handle = channel.open(file.getAbsolutePath(), true);
			} catch (NoSuchFileException e) {
				// like RandomAccessFile in "rw" mode: create it
				channel.createNew(file.getAbsolutePath());   // never truncates one created meanwhile
				fileChanged();
				handle = channel.open(file.getAbsolutePath(), true);
			} catch (AccessDeniedException e) {
				// No write permission: open it read-only, so it can still be read.
				// Writes will then fail with the server's error.
				handle = channel.open(file.getAbsolutePath(), false);
			}
		}
		return handle;
	}

	/**
	 * Saves pending changes, then closes the remote file and this
	 * controller's SFTP channel.
	 */
	@Override
	public void close() throws Exception {
		try {
			super.close();
		} finally {
			try {
				if( handle != null ) {
					handle.close();
				}
			} finally {
				handle = null;
				channel.close();
			}
		}
	}

}
