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

	/** System property naming the default implementation: jsch or mina. */
	public static final String SYSTEM_PROPERTY = "bjl.sftp.implementation";
	public static final String JSCH = "jsch";
	public static final String MINA = "mina";
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

	/**
	 * @param name "jsch" or "mina" (case-insensitive); null or empty for the default
	 * @throws IllegalArgumentException for any other name
	 */
	public static SshProvider get(String name) {
		if( name == null || name.trim().isEmpty()) {
			name = defaultName();
		}
		switch (name.trim().toLowerCase(Locale.ROOT)) {
		case JSCH: return new JschProvider();
		case MINA: return new MinaProvider();
		default:
			throw new IllegalArgumentException("Unknown SFTP implementation '"+name+"'; use "+JSCH+" or "+MINA);
		}
	}
}
