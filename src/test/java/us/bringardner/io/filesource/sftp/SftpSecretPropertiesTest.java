package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import us.bringardner.io.filesource.sftp.SftpFileSourceFactory;

/** BJL-21: the factory names its secret connection properties exactly. */
public class SftpSecretPropertiesTest {

	@Test
	public void secretPropertiesAreExactlyTheseOnes() {
		SftpFileSourceFactory factory = new SftpFileSourceFactory();
		List<String> secrets = Arrays.asList(SftpFileSourceFactory.PROP_PASSWORD, SftpFileSourceFactory.PROP_PRIVATE_KEY, SftpFileSourceFactory.PROP_SESSION_KEY);
		TreeSet<String> names = new TreeSet<>(Arrays.asList(SftpFileSourceFactory.PROP_PRIVATE_KEY_FILE_NAME, SftpFileSourceFactory.PROP_KNOWN_HOSTS, SftpFileSourceFactory.PROP_USER, SftpFileSourceFactory.PROP_HOST));
		names.addAll(secrets);
		names.addAll(factory.getConnectProperties().stringPropertyNames());
		for(String name : names) {
			assertEquals(secrets.contains(name), factory.isSecretProperty(name), name);
		}
	}
}
