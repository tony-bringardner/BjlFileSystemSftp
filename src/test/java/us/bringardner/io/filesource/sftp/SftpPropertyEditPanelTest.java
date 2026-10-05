package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * The settings panel starts empty: it used to be filled in with the test
 * account ("unittest1" on "localhost"), which a real user then had to
 * notice and replace.
 */
public class SftpPropertyEditPanelTest {

	@Test
	void newPanelHasNoUserHostOrCredentials() {
		Properties p = new SftpPropertyEditPanel().getProperties();
		assertEquals("", p.getProperty(SftpFileSourceFactory.PROP_USER), "user");
		assertEquals("", p.getProperty(SftpFileSourceFactory.PROP_HOST), "host");
		assertEquals("", p.getProperty(SftpFileSourceFactory.PROP_PASSWORD), "password");
		assertEquals("", p.getProperty(SftpFileSourceFactory.PROP_PRIVATE_KEY), "private key");
		assertEquals("", p.getProperty(SftpFileSourceFactory.PROP_PRIVATE_KEY_FILE_NAME), "identity file");
		assertEquals(""+SftpFileSourceFactory.DEFAULT_PORT, p.getProperty(SftpFileSourceFactory.PROP_PORT),
				"the standard port is still filled in");
	}

	@Test
	void setPropertiesStillFillsTheFields() {
		SftpPropertyEditPanel panel = new SftpPropertyEditPanel();
		Properties in = new Properties();
		in.setProperty(SftpFileSourceFactory.PROP_USER, "someone");
		in.setProperty(SftpFileSourceFactory.PROP_HOST, "example.org");
		in.setProperty(SftpFileSourceFactory.PROP_PORT, "2222");
		panel.setProperties(in);
		Properties out = panel.getProperties();
		assertEquals("someone", out.getProperty(SftpFileSourceFactory.PROP_USER));
		assertEquals("example.org", out.getProperty(SftpFileSourceFactory.PROP_HOST));
		assertEquals("2222", out.getProperty(SftpFileSourceFactory.PROP_PORT));
	}
}
