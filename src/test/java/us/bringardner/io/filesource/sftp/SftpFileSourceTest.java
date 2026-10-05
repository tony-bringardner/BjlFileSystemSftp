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
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer).
 */
public class SftpFileSourceTest extends FileSourceAbstractTestClass {

	@BeforeAll
	public static void setUp() throws IOException {
		localTestFileDirPath = "TestFiles";
		localCacheDirPath = "target/SftpFileSourceTestCache";
		remoteTestFileDirPath = "SftpFileSourceTest";

		SftpFileSourceFactory sftp = TestServer.connect(null);
		factory = sftp;
	}
}
