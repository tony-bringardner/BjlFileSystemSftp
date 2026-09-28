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
import java.util.Arrays;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

import us.bringardner.io.filesource.AbstractRandomAccessIoController;
import us.bringardner.io.filesource.FileSource;

public class SftpRandomAccessIoController extends AbstractRandomAccessIoController {

	private SftpFileSourceFactory myFactory;
	private ChannelSftp channel;
	private byte[] _handle;


	public SftpRandomAccessIoController(FileSource file) throws IOException {
		super(file);
		myFactory = (SftpFileSourceFactory) file.getFileSourceFactory();		
		try {
			channel = (ChannelSftp) myFactory.getSession().openChannel("sftp");
			channel.connect();
		} catch (JSchException  e) {
			throw new IOException(e);
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
			byte[] part = channel.readFileChunk(getHandle(), start+got, want-got);
			if( part.length == 0 ) {
				break; // EOF
			}
			System.arraycopy(part, 0, ret, got, part.length);
			got += part.length;
		}
		if( got == 0 ) {
			throw new IOException("Unexpected end of file at "+start+" (length was "+length()
					+"); was "+file.getAbsolutePath()+" changed while it was open?");
		}
		return got == want ? ret : Arrays.copyOf(ret, got);
	}

	@Override
	protected void writeChunk(Chunk chunk) throws IOException {
		channel.writeFileChunk(getHandle(), chunk.start, chunk.data);
		fileChanged();

	}

	@Override
	public void setLength0(long newLength) throws IOException {
		long len = length();
		if( len != newLength) {
			if( newLength< length()) {
				shrinkTo(newLength);
			} else {
				expandTo(newLength);
			}
			fileChanged();
		}
	}

	private void expandTo(long newLength) throws IOException {
		byte [] data = new byte[1];
		channel.writeFileChunk(getHandle(), newLength-1, data);
	}


	/**
	 * Truncates with an SFTP SETSTAT carrying only the new size. This used to run
	 * "truncate -s N path" in a shell: the path wasn't quoted, it needed shell
	 * access, and it guessed the exit status after a fixed 100 ms sleep.
	 */
	private void shrinkTo(long newLength) throws IOException {
		channel.setSize(file.getAbsolutePath(), newLength);
	}

	/** The file's size or times changed, so its cached attributes are stale. */
	private void fileChanged() {
		if (file instanceof SftpFileSource) {
			((SftpFileSource) file).clearAttr();
		}
	}

	private byte[] getHandle() throws IOException {
		if( _handle == null ) {
			try {
				_handle = channel.openFile(file.getAbsolutePath());
			} catch (IOException e) {
				// No write permission: open it read-only, so it can still be read.
				// Writes will then fail with the server's error.
				if( e.getCause() instanceof SftpException 
						&& ((SftpException) e.getCause()).id == ChannelSftp.SSH_FX_PERMISSION_DENIED) {
					_handle = channel.openFileForRead(file.getAbsolutePath());
				} else {
					throw e;
				}
			}
		}
		return _handle;
	}

	/**
	 * Saves pending changes, then closes the remote file handle and this
	 * controller's SFTP channel. They used to stay open until the session
	 * ended, and OpenSSH allows only 10 channels per connection by default.
	 */
	@Override
	public void close() throws Exception {
		try {
			super.close();
		} finally {
			try {
				if( _handle != null ) {
					channel.closeFile(_handle);
				}
			} finally {
				_handle = null;
				channel.disconnect();
			}
		}
	}

}
