package us.bringardner.io.filesource.sftp.client.jsch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SshConnection;

class JschConnection implements SshConnection {

	private final Session session;
	private final int timeoutMs;

	JschConnection(Session session, int timeoutMs) {
		this.session = session;
		this.timeoutMs = timeoutMs;
	}

	@Override
	public boolean isConnected() {
		return session.isConnected();
	}

	@Override
	public SftpChannel openSftp() throws IOException {
		try {
			ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
			channel.connect(timeoutMs);
			return new JschSftpChannel(channel);
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	@Override
	public ExecResult exec(String command, long commandTimeoutMs) throws IOException {
		ChannelExec exec = null;
		try {
			exec = (ChannelExec) session.openChannel("exec");
			exec.setCommand(command);
			// JSch writes stderr into this buffer itself, so a command that
			// fills stderr can't block while stdout is being read.
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			exec.setErrStream(err, true);
			InputStream stdOut = exec.getInputStream();
			exec.connect(timeoutMs);

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int read;
			while( (read = stdOut.read(buffer)) >= 0 ) {
				out.write(buffer, 0, read);
			}

			long end = System.currentTimeMillis() + commandTimeoutMs;
			while( !exec.isClosed()) {
				if( System.currentTimeMillis() > end ) {
					throw new IOException("Command did not finish within "+commandTimeoutMs/1000+" s: "+command);
				}
				Thread.sleep(10);
			}
			return new ExecResult(exec.getExitStatus(),
					out.toString(StandardCharsets.UTF_8.name()),
					err.toString(StandardCharsets.UTF_8.name()));
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for: "+command);
		} finally {
			if( exec != null ) {
				exec.disconnect();
			}
		}
	}

	@Override
	public void close() {
		session.disconnect();
	}
}
