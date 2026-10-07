package us.bringardner.io.filesource.sftp.client;

import java.util.Locale;

import us.bringardner.io.filesource.sftp.client.jsch.JschProvider;
import us.bringardner.io.filesource.sftp.client.mina.MinaProvider;

/**
 * Chooses the SSH library at run time.
 * <p>
 * The name comes from, in order: the value passed in (the factory's
 * "implementation" connection property), the system property
 * {@value #SYSTEM_PROPERTY}, then {@value #DEFAULT}.
 */
public final class SshProviders {

	/** System property naming the default implementation: jsch, mina or bjl. */
	public static final String SYSTEM_PROPERTY = "bjl.sftp.implementation";
	public static final String JSCH = "jsch";
	public static final String MINA = "mina";
	/** The BJL SSH library (bjl_net_ssh), no third party code */
	public static final String BJL = "bjl";
	public static final String DEFAULT = JSCH;

	private SshProviders() {
	}

	/**
	 * The implementation to use when none is configured on the factory:
	 * the system property if set, otherwise jsch.
	 */
	public static String defaultName() {
		String name = System.getProperty(SYSTEM_PROPERTY);
		return name == null || name.trim().isEmpty() ? DEFAULT : name.trim().toLowerCase(Locale.ROOT);
	}

	/** The known_hosts file used for host key checking: the setting, else ~/.ssh/known_hosts. */
	public static String knownHostsFile(SshSettings s) {
		return s.knownHosts != null && !s.knownHosts.isEmpty()
				? s.knownHosts
				: System.getProperty("user.home")+"/.ssh/known_hosts";
	}

	/**
	 * The error for a server whose host key the known_hosts file doesn't
	 * vouch for, saying what to do about it. The libraries' own messages
	 * ("reject HostKey: host", "Server key did not validate") don't.
	 */
	public static java.io.IOException hostKeyRejected(SshSettings s, String detail, Throwable cause) {
		String file = knownHostsFile(s);
		return new java.io.IOException("The host key of "+s.host+":"+s.port+" isn't in "+file
				+", or doesn't match the one there ("+detail+"). Host keys are checked by default"
				+" (strictHostKeyChecking=yes). If you trust this server, add its key to "+file
				+", for example: ssh-keyscan -p "+s.port+" "+s.host+" >> "+file
				+" (compare the fingerprint with the server's first). If the key was there and"
				+" changed without a reason, the server may be impersonated. Setting"
				+" strictHostKeyChecking=no accepts any server.", cause);
	}

	/**
	 * @param name "jsch", "mina" or "bjl" (case-insensitive); null or empty for the default
	 * @throws IllegalArgumentException for any other name
	 */
	public static SshProvider get(String name) {
		if( name == null || name.trim().isEmpty()) {
			name = defaultName();
		}
		switch (name.trim().toLowerCase(Locale.ROOT)) {
		case JSCH: return new JschProvider();
		case MINA: return new MinaProvider();
		case BJL: return new us.bringardner.io.filesource.sftp.client.bjl.BjlProvider();
		default:
			throw new IllegalArgumentException("Unknown SFTP implementation '"+name+"'; use "+JSCH+", "+MINA+" or "+BJL);
		}
	}
}
