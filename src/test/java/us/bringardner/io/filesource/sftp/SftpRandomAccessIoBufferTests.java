package us.bringardner.io.filesource.sftp;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.IRandomAccessIoController;
import us.bringardner.io.filesource.test.FileSourceRandomAccessIoBufferTests;

/** The shared random access I/O controller tests over SFTP, with small chunks. */
public class SftpRandomAccessIoBufferTests extends FileSourceRandomAccessIoBufferTests {

	@BeforeAll
	public static void setUp() throws IOException {
		remoteTestFileDirPath = "SftpRandomAccessIoBufferTests";
		SftpFileSourceFactory sftp = TestServer.connect(null);
		//  use a small chunk size to generate lot's of activity
		sftp.setChunkSize(100);
		factory = sftp;
	}

	@Override
	protected IRandomAccessIoController getRandomAccessFileStream(FileSource file) throws IOException {
		return new SftpRandomAccessIoController(file);
	}
}
