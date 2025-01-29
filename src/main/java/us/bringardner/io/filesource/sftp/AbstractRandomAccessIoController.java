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

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceFactory;
import us.bringardner.io.filesource.IRandomAccessIoController;

/**
 * This class reads and writes data in Chunks :-) 
 */
public abstract class AbstractRandomAccessIoController implements IRandomAccessIoController {

	protected class Chunk {
		boolean isNew=false;
		boolean isDirty=false;
		byte [] data;
		long chunkNumber;
		long start;
		int size;

		public Chunk() {

		}

		public Chunk(long start,long cn,byte [] b) {
			this.start = start;
			chunkNumber = cn;
			data = b;
		}

		public boolean contains(long pos) {
			long end = (start+data.length);
			boolean b1 = pos >= start ;
			boolean b2 = pos < end;

			return b1
					&& 
					b2;
		}	

	}

	protected FileSourceFactory factory ;
	protected FileSource file;

	private long lastWritePosition;

	private long lastReadPosition;
	private int maxWriteOffset=-1;
	private Chunk currentChunk;
	private boolean closed = false;


	public AbstractRandomAccessIoController(FileSource file) throws IOException {
		this.file = file;
		factory = file.getFileSourceFactory();		
	}



	public FileSource getFile() {
		return file;
	}



	public long getLastWritePosition() {
		return lastWritePosition;
	}



	public long getLastReadPosition() {
		return lastReadPosition;
	}




	public boolean isDirty() {
		return currentChunk !=null && currentChunk.isDirty;
	}

	public boolean contains(long pos) {
		return currentChunk == null ? false: currentChunk.contains(pos);
	}


	public long length() throws IOException {
		file.refresh();
		long ret = file.length();		
		if(currentChunk !=null && currentChunk.isNew) {
			if( maxWriteOffset>=0) {
				ret += maxWriteOffset+1;
			}
		}
		return ret;
	}

	public int read(long pos) throws IOException {
		if(pos<0) {
			throw new IOException("Negative position");
		}

		int ret = -1;
		if( !closed ) {
			if(currentChunk == null || 
					!currentChunk. contains(pos)) {
				// find and load data
				System.out.println("");
				loadChunkFor(pos);

			}

			if( currentChunk.isNew) {
				if(currentChunk.isDirty) {

					int offset = (int)(pos-currentChunk.start);
					if( offset < (maxWriteOffset+1)) {
						ret = currentChunk.data[offset];
						lastReadPosition = pos;
					}				
				} else {
					//  will return -1
				}
			} else if(currentChunk.contains(pos)) {
				int offset = (int)(pos-currentChunk.start);
				ret = currentChunk.data[offset];
				lastReadPosition = pos;
			} else {
				throw new IOException("Logic error");
			}
		}
		return ret;
	}

	public void write(long pos, byte value) throws IOException {
		if(pos<0) {
			throw new IOException("Negative position");
		}
		if( closed ) {
			throw new IOException("Already closed");
		}

		if( currentChunk == null) {
			loadChunkFor(pos);
		}

		if(currentChunk == null || 
				!contains(pos)
				) {			
			loadChunkFor(pos);
		}

		int offset = (int)(pos-currentChunk.start);
		if( offset < 0 ) {
			throw new IOException("Negative offset pos="+pos+" start="+currentChunk.start);
		}
		if( offset >= currentChunk.data.length ) {
			//  We're writing past the end of the file
			//  by more than the chunk size, so expand the file
			setLength(pos-1);
			loadChunkFor(pos);
			offset = (int)(pos-currentChunk.start);
			if( offset < 0 ) {
				throw new IOException("Negative offset after expanding file pos="+pos+" start="+currentChunk.start);
			}
			if( offset >= currentChunk.data.length ) {
				//  we did not solve the problem???
				throw new IOException("offset >= currentChunk.data.length "
						+ " pos="+pos+" start="+currentChunk.start
						+ " offset="+offset
						+ " data.length="+currentChunk.data.length
						);
			}
		}

		currentChunk.data[offset] = value;
		currentChunk.isDirty = true;
		maxWriteOffset = Math.max(offset, maxWriteOffset);
		currentChunk.size = Math.max(offset+1, currentChunk.size);
	}

	private void loadChunkFor(long pos) throws IOException {
		// save any new writes

		if( currentChunk!=null ) {
			if( currentChunk.isDirty)  {
				save();
			} else if(maxWriteOffset!=-1) {
				throw new IOException("MAx write is not 0 but chunk is not dirty");	
			}
		}
		// find and load data
		currentChunk = readChunkForPos(pos);

	}

	protected abstract Chunk readChunkForPos(long pos) throws IOException ;



	public void save() throws IOException {
		if( currentChunk !=null ) {
			if(!currentChunk.isNew && currentChunk.size != currentChunk.data.length) {
				throw new IOException("Chunk size is not valid chunk size="+currentChunk.size+" data.length = "+currentChunk.data.length);
			}
			if(currentChunk.isDirty) {
				if(currentChunk.isNew || currentChunk.chunkNumber<=0) {
					if( maxWriteOffset >= currentChunk.data.length ) {
						throw new IOException("Maxwrite invalid = "+maxWriteOffset+" data.len="+currentChunk.data.length);
					}
					//  chunk is dirty so we know something was written
					//  the offset is 0 based					
					int size = maxWriteOffset+1;
					if( size < currentChunk.data.length) {
						byte [] tmp = new byte[size];
						for (int idx = 0; idx < tmp.length; idx++) {
							tmp[idx] = currentChunk.data[idx];
						}
						currentChunk.data = tmp;
						currentChunk.size = tmp.length;
					}
				}
				writeChunk(currentChunk);
			}
			currentChunk.isDirty = false;
			currentChunk.isNew = false;
			maxWriteOffset = -1;

		}
	}

	protected abstract void writeChunk(Chunk chunk) throws IOException;

	public  void setLength(long newLength) throws IOException{
		if(maxWriteOffset>=0) {
			save();
		}
		setLength0(newLength);
		// force reload of next chunk
		currentChunk = null;
	}




	protected abstract void setLength0(long newLength) throws IOException;



	@Override
	public void close() throws Exception {
		if( !closed ) {
			save();
			closed = true;
		}

	}

}
