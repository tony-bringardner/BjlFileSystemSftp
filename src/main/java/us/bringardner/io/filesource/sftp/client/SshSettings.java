package us.bringardner.io.filesource.sftp.client;

/**
 * Everything needed to open an SSH connection. Implementations read these
 * values and nothing else, so JSch and MINA connect the same way.
 */
public final class SshSettings {

	public String host;
	public int port = 22;
	public String user;
	/** Password, or null for key authentication only. */
	public String password;
	/** Path of a private key file, or null. */
	public String privateKeyFile;
	/** Private key contents (PEM/OpenSSH format), or null. Used instead of privateKeyFile when set. */
	public byte[] privateKey;
	/** known_hosts file used when strictHostKeyChecking is true; null means ~/.ssh/known_hosts. */
	public String knownHosts;
	/** true: reject host keys not in known_hosts. false: accept any host key. */
	public boolean strictHostKeyChecking;
	/** Milliseconds for the TCP connect, handshake, authentication and channel opens; 0 = no limit. */
	public int connectTimeoutMs = 30_000;
	/** Milliseconds between keepalive messages on an idle connection; 0 = none. */
	public int serverAliveIntervalMs = 30_000;

	@Override
	public String toString() {
		return user+"@"+host+":"+port;
	}
}
