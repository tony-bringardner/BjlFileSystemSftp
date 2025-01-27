package us.bringardner.io.filesource.sftp;

import java.io.IOException;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.IRandomAccessIoController;

public class SftpRandomAccessIoController  implements IRandomAccessIoController {

	private SftpFileSource file ;
	private SftpFileSourceFactory factory;
	private ChannelSftp channel;
	private byte[] _handle;
	private boolean closed;
	private byte [] data;
	public String dataString = "";
	private long start;
	private long end;
	private int size;
	private long maxWritePosition = -1;



	public static void main(String[] args) {

	}


	public SftpRandomAccessIoController(SftpFileSource file) throws IOException {
		try {
			this.file = file;
			factory = (SftpFileSourceFactory) file.getFileSourceFactory();		
			channel = (ChannelSftp) factory.getSession().openChannel("sftp");
			channel.connect();
			
		} catch (JSchException e) {
			throw new IOException(e);
		}
	}



	@Override
	public void close() throws Exception {
		save();
		channel.closeFile(getHandle());
		closed = true;		
	}


	private byte[] getHandle() throws IOException {
		if( _handle == null ) {
			_handle = channel.openFile(file.getAbsolutePath());
		}
		return _handle;
	}


	private void readChunkForPos(long pos) throws IOException {
		if( maxWritePosition >=0) {
			save();
		}
		long len = length();
		int chunkSize = factory.getChunkSize();
		if(len == 0 || pos >= len) {
			size = 0;
			data = new byte[chunkSize];
			start = len;
			
		} else {
			int chunk = (int)(pos/chunkSize);
			start = chunk * chunkSize;
			data = channel.readFileChunk(getHandle(), start, chunkSize);
			size = data.length;
			
		}
		dataString = new String(data);
		end = start+data.length;
		
		
	}

	@Override
	public int read(long position) throws IOException {
		int ret = -1;
		if( !closed ) {
			if( maxWritePosition>=0) {
				save();
			}

			if(position< start || position>= end) {
				readChunkForPos(position);
			}
			if( position < end) {
				int offset = (int) (position-start);
				ret = data[offset];
			}
		}

		return ret;
	}


	@Override
	public void write(long position, byte value) throws IOException {
		if( position < start || position>=end) {
			readChunkForPos(position);			
		}
		int offset = (int) (position - start);
		data[offset] = value;
		size = Math.max(size, offset+1);
		maxWritePosition = Math.max(maxWritePosition, position);
	}


	@Override
	public long length() throws IOException {
		long ret = 0;
		file.refresh();
		ret = file.length();

		if( maxWritePosition >=0 && maxWritePosition> ret) {
			ret = maxWritePosition;
		}

		return ret;
	}


	@Override
	public void setLength(long newLength) throws IOException {
		if(maxWritePosition>=0 ) {
			save();
		}
		if( newLength< length()) {
			shrinkTo(newLength);
		} else {
			expandTo(newLength);
		}


	}


	private void expandTo(long newLength) throws IOException {

		long len = length();
		
		long delta = newLength-len;
		byte [] data = new byte[(int)delta];
		data = new byte[1];
		len = newLength;
		channel.writeFileChunk(getHandle(), newLength-1, data);
	}


	private void shrinkTo(long newLength) throws IOException {
		ChannelExec exec = null;

		try {
			exec = (ChannelExec) factory.getSession().openChannel("exec");
			String cmd = "truncate -s "+newLength+" "+file.getAbsolutePath();
			exec.setCommand(cmd);	
			exec.connect();

			int status = exec.getExitStatus();
			if( status != 0 ) {
				throw new IOException("Server does not suppport this function");				
			}

		} catch (JSchException e) {
			throw new IOException(e);
		} finally {
			if( exec != null ) {
				try {
					exec.disconnect();
				} catch (Exception e2) {
				}
			}
		}


	}


	@Override
	public void save() throws IOException {
		if( size != data.length) {
			if( size >= data.length) {
				throw new IOException("Logic error size="+size+" data.len="+data.length);
			}
			
			byte [] tmp = new byte[size];
			for (int idx = 0; idx < tmp.length; idx++) {
				tmp[idx] = data[idx];
			}
			data = tmp;			
		}
		channel.writeFileChunk(getHandle(), start, data);
		maxWritePosition = -1;
	}


	@Override
	public FileSource getFile() {		
		return file;
	}

}
