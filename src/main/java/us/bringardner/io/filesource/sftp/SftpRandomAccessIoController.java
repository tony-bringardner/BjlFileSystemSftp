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

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;

import us.bringardner.io.filesource.AbstractRandomAccessIoController;
import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpFile;

/**
 * Random access to an SFTP file in chunks of the factory's chunk size. Uses
 * its own SFTP channel, so it doesn't compete with the factory's.
 */
public class SftpRandomAccessIoController extends AbstractRandomAccessIoController {

	private final SftpFileSourceFactory myFactory;
	private final SftpChannel channel;
	private SftpFile handle;


	public SftpRandomAccessIoController(FileSource file) throws IOException {
		super(file);
		myFactory = (SftpFileSourceFactory) file.getFileSourceFactory();
		channel = myFactory.getConnection().openSftp();
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
		getHandle().write(chunk.start, chunk.data, 0, chunk.data.length);
		fileChanged();

	}

	@Override
	public void setLength0(long newLength) throws IOException {
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
			try {
				handle = channel.open(file.getAbsolutePath(), true);
			} catch (NoSuchFileException e) {
				// like RandomAccessFile in "rw" mode: create it
				channel.write(file.getAbsolutePath(), false).close();
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
