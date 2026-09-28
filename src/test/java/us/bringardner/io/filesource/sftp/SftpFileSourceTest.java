package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;

/**
 * Runs the general FileSource tests in FileSourceAbstractTestClass (roots,
 * copying a directory tree up and back, rename, delete, permissions) against
 * SFTP. Nothing extended that class before, so these tests never ran.
 *
 * Uses the same local SSH server and account as the other SFTP tests
 * (localhost:22, unittest1 / 0000).
 */
public class SftpFileSourceTest extends FileSourceAbstractTestClass {

	@BeforeAll
	public static void setUp() throws IOException {
		localTestFileDirPath = "TestFiles";
		localCacheDirPath = "target/SftpFileSourceTestCache";
		remoteTestFileDirPath = "SftpFileSourceTest";

		SftpFileSourceFactory sftp = new SftpFileSourceFactory();
		Properties p = sftp.getConnectProperties();
		p.setProperty("user", "unittest1");
		p.setProperty("host", "localhost");
		p.setProperty("port", "22");
		p.setProperty("password", "0000");
		sftp.setConnectionProperties(p);
		assertTrue(sftp.connect(), "Factory did not connect");
		factory = sftp;
	}
}
