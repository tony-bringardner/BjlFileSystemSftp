package us.bringardner.io.filesource.sftp.client.bjl;

import java.io.IOException;

import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SshConnection;
import us.bringardner.net.ssh.client.ClientSession;
import us.bringardner.net.ssh.client.SshClient;
import us.bringardner.net.ssh.sftp.SftpClient;

class BjlConnection implements SshConnection {

	private final SshClient client;
	private final ClientSession session;

	BjlConnection(SshClient client, ClientSession session) {
		this.client = client;
		this.session = session;
	}

	@Override
	public boolean isConnected() {
		return session.isOpen();
	}

	@Override
	public SftpChannel openSftp() throws IOException {
		return new BjlSftpChannel(SftpClient.open(session));
	}

	@Override
	public ExecResult exec(String command, long timeoutMs) throws IOException {
		us.bringardner.net.ssh.client.ExecResult r;
		try {
			r = session.exec(command, null, timeoutMs);
		} catch (java.net.SocketTimeoutException e) {
			throw new IOException("Command did not finish within "+timeoutMs/1000+" s: "+command, e);
		}
		Integer status = r.getExitStatus();
		return new ExecResult(status == null ? -1 : status, r.getStdoutText(), r.getStderrText());
	}

	@Override
	public void close() {
		try {
			session.close();
		} finally {
			client.close();
		}
	}
}
