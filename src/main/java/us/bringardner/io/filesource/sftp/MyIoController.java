package us.bringardner.io.filesource.sftp;

import java.io.IOException;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;

import us.bringardner.io.filesource.FileSource;

public class MyIoController extends AbstractRandomAccessIoController {

	private SftpFileSourceFactory myFactory;
	private ChannelSftp channel;
	private byte[] _handle;


	public MyIoController(FileSource file) throws IOException {
		super(file);
		myFactory = (SftpFileSourceFactory) file.getFileSourceFactory();		
		try {
			channel = (ChannelSftp) myFactory.getSession().openChannel("sftp");
			channel.connect();
		} catch (JSchException  e) {
			throw new IOException(e);
		}

	}

	String dataString="";
	
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
			int chunk = (int)(pos/chunkSize);
			ret.start = chunk * chunkSize;
			ret.data = channel.readFileChunk(getHandle(), ret.start, chunkSize);
			ret.size = ret.data.length;			
		}
		dataString = new String(ret.data);
		
		
		return ret;
	}

	@Override
	protected void writeChunk(Chunk chunk) throws IOException {
		channel.writeFileChunk(getHandle(), chunk.start, chunk.data);
		
	}

	@Override
	public void setLength0(long newLength) throws IOException {
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
			exec = (ChannelExec) myFactory.getSession().openChannel("exec");
			String cmd = "truncate -s "+newLength+" "+file.getAbsolutePath();
			exec.setCommand(cmd);	
			exec.connect();
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
			int status = exec.getExitStatus();
			if( status != 0 ) {
				throw new IOException("Server does not support this function");				
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
	

	
	private byte[] getHandle() throws IOException {
		if( _handle == null ) {
			_handle = channel.openFile(file.getAbsolutePath());
		}
		return _handle;
	}

}
