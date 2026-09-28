package us.bringardner.io.filesource.sftp.client.mina;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.SftpVersionSelector;

import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SshConnection;

class MinaConnection implements SshConnection {

	private final SshClient client;
	private final ClientSession session;
	private final Duration timeout;

	MinaConnection(SshClient client, ClientSession session, Duration timeout) {
		this.client = client;
		this.session = session;
		this.timeout = timeout;
	}

	@Override
	public boolean isConnected() {
		return session.isOpen();
	}

	@Override
	public SftpChannel openSftp() throws IOException {
		// SFTP version 3: numeric uid/gid and the ls -l long names, like OpenSSH
		// and JSch. Later versions send owner/group as names instead.
		SftpClient sftp = SftpClientFactory.instance().createSftpClient(session, SftpVersionSelector.fixedVersionSelector(3));
		return new MinaSftpChannel(sftp);
	}

	@Override
	public ExecResult exec(String command, long commandTimeoutMs) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try (ChannelExec exec = session.createExecChannel(command)) {
			exec.setOut(out);
			exec.setErr(err);
			exec.open().verify(timeout);
			Set<ClientChannelEvent> events = exec.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), commandTimeoutMs);
			if( !events.contains(ClientChannelEvent.CLOSED)) {
				throw new IOException("Command did not finish within "+commandTimeoutMs/1000+" s: "+command);
			}
			Integer status = exec.getExitStatus();
			return new ExecResult(status == null ? -1 : status,
					out.toString(StandardCharsets.UTF_8.name()),
					err.toString(StandardCharsets.UTF_8.name()));
		}
	}

	@Override
	public void close() {
		try {
			session.close(false);
		} finally {
			client.stop();
		}
	}
}
