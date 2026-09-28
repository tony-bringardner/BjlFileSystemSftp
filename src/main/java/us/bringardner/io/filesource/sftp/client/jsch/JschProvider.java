package us.bringardner.io.filesource.sftp.client.jsch;

import java.io.IOException;
import java.util.Properties;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import us.bringardner.io.filesource.sftp.client.SshConnection;
import us.bringardner.io.filesource.sftp.client.SshProvider;
import us.bringardner.io.filesource.sftp.client.SshProviders;
import us.bringardner.io.filesource.sftp.client.SshSettings;

/** SSH through JSch (the maintained com.github.mwiede fork, or the original 0.1.55). */
public class JschProvider implements SshProvider {

	@Override
	public String getName() {
		return SshProviders.JSCH;
	}

	@Override
	public SshConnection connect(SshSettings s) throws IOException {
		try {
			// A new JSch per connection, so identities don't pile up across connects
			JSch jsch = new JSch();
			if( s.strictHostKeyChecking ) {
				jsch.setKnownHosts(s.knownHosts != null && !s.knownHosts.isEmpty()
						? s.knownHosts
						: System.getProperty("user.home")+"/.ssh/known_hosts");
			}
			if( s.privateKey != null ) {
				jsch.addIdentity(null, s.privateKey, null, null);
			} else if( s.privateKeyFile != null && !s.privateKeyFile.isEmpty()) {
				jsch.addIdentity(s.privateKeyFile);
			}

			Session session = jsch.getSession(s.user, s.host, s.port);
			Properties config = new Properties();
			config.put("StrictHostKeyChecking", s.strictHostKeyChecking ? "yes" : "no");
			session.setConfig(config);
			if( s.password != null ) {
				session.setPassword(s.password);
			}
			if( s.serverAliveIntervalMs > 0 ) {
				session.setServerAliveInterval(s.serverAliveIntervalMs);
			}
			session.connect(s.connectTimeoutMs);
			return new JschConnection(session, s.connectTimeoutMs);
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}
	}
}
