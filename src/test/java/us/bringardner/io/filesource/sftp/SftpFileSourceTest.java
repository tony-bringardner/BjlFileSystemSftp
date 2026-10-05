package us.bringardner.io.filesource.sftp;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.io.filesource.test.AbstractTestClass;

/** The shared FileSource tests over SFTP (see TestServer for the server). */
public class SftpFileSourceTest extends AbstractTestClass {

	@BeforeAll
	public static void setUp() throws IOException {
		localTestFileDirPath = "TestFiles";
		localCacheDirPath = "target/SftpFileSourceTestCache";
		remoteTestFileDirPath = "SftpFileSourceTest";
		factory = TestServer.connect(null);
	}

	/**
	 * getCanonicalPath() looks at every directory from the root down, which
	 * the embedded server's sandbox refuses above its home directory.
	 */
	@Override
	@Test
	public void testIsChildOfMineContract() throws IOException {
		TestServer.assumeOpenSsh();
		super.testIsChildOfMineContract();
	}
}
