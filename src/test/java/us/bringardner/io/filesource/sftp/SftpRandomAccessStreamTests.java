package us.bringardner.io.filesource.sftp;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.io.filesource.test.AbstractRandomAccessStreamTests;

/** The shared random access stream tests over SFTP, with small chunks. */
public class SftpRandomAccessStreamTests extends AbstractRandomAccessStreamTests {

	@BeforeAll
	public static void setUp() throws IOException {
		remoteTestFileDirPath = "SftpRandomAccessStreamTests";
		SftpFileSourceFactory sftp = TestServer.connect(null);
		//  use a small chunk size to generate lot's of activity
		sftp.setChunkSize(100);
		factory = sftp;
	}
}
