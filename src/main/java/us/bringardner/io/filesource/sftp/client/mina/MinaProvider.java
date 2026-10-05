package us.bringardner.io.filesource.sftp.client.mina;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;

import us.bringardner.io.filesource.sftp.client.SshConnection;
import us.bringardner.io.filesource.sftp.client.SshProvider;
import us.bringardner.io.filesource.sftp.client.SshProviders;
import us.bringardner.io.filesource.sftp.client.SshSettings;

/** SSH through Apache MINA SSHD. */
public class MinaProvider implements SshProvider {

	/** Unanswered keepalives before the connection is dropped (OpenSSH's default). */
	static final int SERVER_ALIVE_COUNT_MAX = 3;

	@Override
	public String getName() {
		return SshProviders.MINA;
	}

	@Override
	public SshConnection connect(SshSettings s) throws IOException {
		// One client per connection: the host-key policy is set on the client,
		// and it differs between connections.
		SshClient client = SshClient.setUpDefaultClient();
		boolean ok = false;
		try {
			if( s.strictHostKeyChecking ) {
				Path knownHosts = s.knownHosts != null && !s.knownHosts.isEmpty()
						? Paths.get(s.knownHosts)
						: Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts");
				client.setServerKeyVerifier(new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, knownHosts));
			} else {
				client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
			}
			if( s.serverAliveIntervalMs > 0 ) {
				CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofMillis(s.serverAliveIntervalMs));
				// Ask for a reply and give up after this many go unanswered, like
				// OpenSSH's ServerAliveCountMax. Without it MINA's heartbeats are
				// one-way, so a dead connection could look alive for many minutes.
				CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(client, SERVER_ALIVE_COUNT_MAX);
			}
			client.start();

			Duration timeout = s.connectTimeoutMs > 0 ? Duration.ofMillis(s.connectTimeoutMs) : Duration.ofDays(365);
			ClientSession session = client.connect(s.user, s.host, s.port).verify(timeout).getSession();
			try {
				if( s.password != null ) {
					session.addPasswordIdentity(s.password);
				}
				for (KeyPair kp : loadKeys(s)) {
					session.addPublicKeyIdentity(kp);
				}
				session.auth().verify(timeout);
			} catch (IOException | RuntimeException e) {
				session.close(true);
				throw e;
			}
			ok = true;
			return new MinaConnection(client, session, timeout);
		} finally {
			if( !ok ) {
				client.stop();
			}
		}
	}

	private static Iterable<KeyPair> loadKeys(SshSettings s) throws IOException {
		InputStream in = null;
		String name = null;
		if( s.privateKey != null ) {
			in = new ByteArrayInputStream(s.privateKey);
			name = "privateKey";
		} else if( s.privateKeyFile != null && !s.privateKeyFile.isEmpty()) {
			in = Files.newInputStream(Paths.get(s.privateKeyFile));
			name = s.privateKeyFile;
		}
		if( in == null ) {
			return java.util.Collections.emptyList();
		}
		try (InputStream is = in) {
			Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(name), is, null);
			return keys == null ? java.util.Collections.emptyList() : keys;
		} catch (GeneralSecurityException e) {
			throw new IOException("Can't load private key "+name+": "+e.getMessage(), e);
		}
	}
}
