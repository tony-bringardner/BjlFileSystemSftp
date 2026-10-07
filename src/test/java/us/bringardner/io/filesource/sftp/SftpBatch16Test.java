package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.sftp.client.SftpChannel;

/**
 * Batch 16: createNewFile never empties an existing file, renameTo stays on
 * one server, and the permission and time getters answer for a missing file
 * instead of throwing NullPointerException. Each test runs with both SSH
 * libraries. (Pipelined stream reads are in SftpReadAheadTest.)
 * <p>
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer).
 */
public class SftpBatch16Test {

	static final String DIR = "SftpBatch16Test";

	static SftpFileSourceFactory jsch;
	static SftpFileSourceFactory mina;
	static SftpFileSourceFactory bjl;

	@BeforeAll
	static void setUp() throws IOException {
		jsch = TestServer.connect("jsch");
		mina = TestServer.connect("mina");
		bjl = TestServer.connect("bjl");
		FileSource dir = jsch.createFileSource(DIR);
		if( !dir.exists()) {
			assertTrue(dir.mkdirs(), "Can't create "+dir);
		}
	}

	@AfterAll
	static void tearDown() throws IOException {
		if( mina != null ) {
			mina.disConnect();
		}
		if( bjl != null ) {
			bjl.disConnect();
		}
		if( jsch != null ) {
			SftpRandomAccessTest.deleteAll(jsch.createFileSource(DIR));
			jsch.disConnect();
		}
	}

	static SftpFileSourceFactory factory(String impl) {
		return impl.equals("jsch") ? jsch : impl.equals("mina") ? mina : bjl;
	}

	/** An empty directory for one test, and its absolute path. */
	static String freshDir(String impl, String name) throws IOException {
		FileSource d = jsch.createFileSource(DIR+"/"+impl+"-"+name);
		if( d.exists()) {
			SftpRandomAccessTest.deleteAll(d);
		}
		assertTrue(d.mkdirs());
		return d.getAbsolutePath();
	}

	static byte[] bytes(String s) throws IOException {
		return s.getBytes("UTF-8");
	}

	// ------------------------------------------------------------ createNewFile

	/**
	 * The old createNewFile trusted the cached exists() and then opened with
	 * truncate, so a file created since the cache was filled was emptied.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void createNewFileNeverEmptiesAnExistingFile(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "create-race");
		FileSource mine = f.createFileSource(d+"/x.txt");
		assertFalse(mine.exists());   // now cached as missing
		SftpRegressionTest.write(f.createFileSource(d+"/x.txt"), bytes("keep me"));   // someone else creates it

		assertFalse(mine.createNewFile(), "it already existed");
		assertArrayEquals(bytes("keep me"), SftpRegressionTest.read(f.createFileSource(d+"/x.txt")));
	}

	/** Like java.io.File: true when it creates the file, false when it was already there. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void createNewFileCreatesOnce(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "create-once");
		FileSource x = f.createFileSource(d+"/x.txt");
		assertTrue(x.createNewFile());
		assertTrue(x.exists());
		assertEquals(0, x.length());
		assertFalse(x.createNewFile(), "the second call finds it");
	}

	/** The channel call itself: MINA's exclusive open and JSch's check-then-append. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void channelCreateNewKeepsExistingData(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "create-channel");
		SftpRegressionTest.write(f.createFileSource(d+"/x.txt"), bytes("data"));
		try (SftpChannel c = f.openSftp()) {
			assertFalse(c.createNew(d+"/x.txt"));
			assertTrue(c.createNew(d+"/y.txt"));
		}
		assertArrayEquals(bytes("data"), SftpRegressionTest.read(f.createFileSource(d+"/x.txt")));
		assertEquals(0, f.createFileSource(d+"/y.txt").length());
	}

	/** "rw" random access on an existing file opens it; it's not emptied. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void randomAccessRwKeepsExistingData(String impl) throws Exception {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "create-rw");
		try (var r = f.createFileSource(d+"/new.bin").getRandomAccessStream("rw")) {
			r.write(bytes("abc"));
		}
		assertArrayEquals(bytes("abc"), SftpRegressionTest.read(f.createFileSource(d+"/new.bin")));
		try (var r = f.createFileSource(d+"/new.bin").getRandomAccessStream("rw")) {
			assertEquals(3, r.length());
		}
		assertArrayEquals(bytes("abc"), SftpRegressionTest.read(f.createFileSource(d+"/new.bin")));
	}

	// ------------------------------------------------------------ renameTo

	/**
	 * A destination from another server account must not be renamed to on
	 * this one. The other factory names the same server as 127.0.0.1, which
	 * isSameFileSystem treats as a different server, so the old code's rename
	 * would have happened here.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void renameToAnotherServerDoesNothing(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "rename");
		SftpFileSourceFactory other = new SftpFileSourceFactory();
		Properties p = TestServer.properties(impl, TestServer.port());
		p.setProperty(SftpFileSourceFactory.PROP_HOST, "127.0.0.1");
		other.setConnectionProperties(p);
		try {
			assertFalse(other.isSameFileSystem(f));
			FileSource src = SftpRegressionTest.write(f.createFileSource(d+"/a.txt"), bytes("a"));
			assertFalse(src.renameTo(other.createFileSource(d+"/b.txt")));
			assertTrue(f.createFileSource(d+"/a.txt").exists());
			assertFalse(f.createFileSource(d+"/b.txt").exists());

			// the same server account still renames
			assertTrue(src.renameTo(f.createFileSource(d+"/b.txt")));
			assertTrue(f.createFileSource(d+"/b.txt").exists());
		} finally {
			other.disConnect();
		}
	}

	// ------------------------------------------------------------ missing files

	/** These used to throw NullPointerException for a file that doesn't exist. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void missingFileAnswersInsteadOfThrowing(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "missing");
		FileSource x = f.createFileSource(d+"/nothing.txt");
		assertFalse(x.canOwnerRead());
		assertFalse(x.canOwnerWrite());
		assertFalse(x.canOwnerExecute());
		assertFalse(x.canGroupRead());
		assertFalse(x.canGroupWrite());
		assertFalse(x.canGroupExecute());
		assertFalse(x.canOtherRead());
		assertFalse(x.canOtherWrite());
		assertFalse(x.canOtherExecute());
		assertEquals(0, x.lastAccessTime());
	}
}
