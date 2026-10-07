package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpEntry;
import us.bringardner.io.filesource.sftp.client.SshConnection;

/**
 * Batch 21: mkdirs when another program makes the directory meanwhile, a
 * factory dropped without disConnect() lets go of its connection, factories
 * serialize again, and the opt-in safeOverwrite (write a temporary file,
 * then rename it over the target). Tests that go through an SSH library
 * run with both.
 * <p>
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer). Links and Unix permissions need OpenSSH.
 */
public class SftpBatch21Test {

	static final String DIR = "SftpBatch21Test";
	static final AtomicInteger KEYS = new AtomicInteger();

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

	/** A connected factory with a connection of its own and the given extra properties. */
	static SftpFileSourceFactory own(String impl, String... props) throws IOException {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		Properties p = TestServer.properties(impl, TestServer.port());
		p.setProperty(SftpFileSourceFactory.PROP_SESSION_KEY, "batch21-"+impl+"-"+KEYS.incrementAndGet()+"-"+System.nanoTime());
		for (int i = 0; i < props.length; i += 2) {
			p.setProperty(props[i], props[i + 1]);
		}
		f.setConnectionProperties(p);
		assertTrue(f.connect());
		return f;
	}

	static SftpFileSourceFactory safe(String impl) throws IOException {
		return own(impl, SftpFileSourceFactory.PROP_SAFE_OVERWRITE, "true");
	}

	static byte[] bytes(String s) throws IOException {
		return s.getBytes("UTF-8");
	}

	/** Names in 'dir', without "." and "..". */
	static List<String> names(SftpFileSourceFactory f, String dir) throws IOException {
		List<String> ret = new ArrayList<>();
		for (SftpEntry e : f.ls(dir)) {
			if( !e.getFilename().equals(".") && !e.getFilename().equals("..")) {
				ret.add(e.getFilename());
			}
		}
		return ret;
	}

	// ------------------------------------------------------------ #11 mkdirs

	/**
	 * exists() said "missing" (and cached it), then another program made the
	 * directory: mkdirs() threw, since mkdir fails on a directory that exists.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void mkdirsSeesADirectoryMadeMeanwhile(String impl) throws IOException {
		String d = freshDir(impl, "mkdirs");
		FileSource mine = factory(impl).createFileSource(d+"/a/b");
		FileSource parent = factory(impl).createFileSource(d+"/a");
		assertFalse(mine.exists());
		assertFalse(parent.exists());
		assertTrue(jsch.createFileSource(d+"/a/b").mkdirs(), "the other program");
		assertTrue(mine.mkdirs());
		assertTrue(parent.mkdirs());
		assertTrue(mine.isDirectory());
	}

	/** Like java.io.File: a file in the way is "false". It used to say true. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void mkdirsOnAFileIsFalse(String impl) throws IOException {
		String d = freshDir(impl, "mkdirs-file");
		FileSource f = SftpRegressionTest.write(factory(impl).createFileSource(d+"/x"), bytes("file"));
		assertFalse(f.mkdirs());
		assertFalse(f.isDirectory());
	}

	// ------------------------------------------------------------ #11 abandoned factories

	/** Connects a factory nobody keeps, and returns only its connection. */
	static SshConnection connectAndDrop(String impl) throws IOException {
		return own(impl).getConnection();
	}

	/**
	 * A factory garbage collected without disConnect() kept its share of the
	 * connection forever, so the connection stayed open until the JVM exited.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void droppedFactoryLetsGoOfItsConnection(String impl) throws Exception {
		SshConnection c = connectAndDrop(impl);
		assertTrue(c.isConnected());
		long deadline = System.currentTimeMillis() + 20_000;
		while( c.isConnected() && System.currentTimeMillis() < deadline ) {
			System.gc();
			Thread.sleep(100);
		}
		assertFalse(c.isConnected(), "still connected 20 s after the factory was dropped");
	}

	/** A factory that's still in use keeps its connection, however often the collector runs. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void factoryInUseKeepsItsConnection(String impl) throws Exception {
		SftpFileSourceFactory f = own(impl);
		try {
			for (int i = 0; i < 5; i++) {
				System.gc();
				Thread.sleep(50);
			}
			assertTrue(f.isConnected());
			assertTrue(f.createFileSource(DIR).isDirectory());
		} finally {
			f.disConnect();
		}
	}

	// ------------------------------------------------------------ serialization

	@SuppressWarnings("unchecked")
	static <T> T roundTrip(T obj) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(obj);
		}
		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (T) in.readObject();
		}
	}

	/**
	 * FileSourceFactory is Serializable. Batch 20's lock (a plain Object)
	 * stopped an SFTP factory being serialized at all; a connected one never
	 * could be. Now it comes back with its settings, disconnected, and can
	 * connect again.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void factoriesSerialize(String impl) throws Exception {
		SftpFileSourceFactory f = own(impl, SftpFileSourceFactory.PROP_MAX_CHANNELS, "6");
		try {
			SftpFileSourceFactory copy = roundTrip(f);
			assertEquals(f.getConnectProperties(), copy.getConnectProperties());
			assertFalse(copy.isConnected());
			assertTrue(copy.connect());
			try {
				assertTrue(copy.createFileSource(DIR).isDirectory());
			} finally {
				copy.disConnect();
			}
		} finally {
			f.disConnect();
		}
		SftpFileSourceFactory unconnected = new SftpFileSourceFactory();
		unconnected.setConnectionProperties(TestServer.properties(impl, TestServer.port()));
		assertEquals(unconnected.getConnectProperties(), roundTrip(unconnected).getConnectProperties());
	}

	// ------------------------------------------------------------ #9 safeOverwrite

	/** Until close(), readers see the old file; after it, the new one, and no temporary file is left. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void safeOverwriteKeepsTheOldFileUntilClose(String impl) throws IOException {
		String d = freshDir(impl, "safe");
		SftpFileSourceFactory f = safe(impl);
		try {
			FileSource x = SftpRegressionTest.write(f.createFileSource(d+"/x.txt"), bytes("old contents"));
			try (OutputStream out = f.createFileSource(d+"/x.txt").getOutputStream()) {
				out.write(bytes("new"));
				out.flush();
				assertArrayEquals(bytes("old contents"), SftpRegressionTest.read(jsch.createFileSource(d+"/x.txt")),
						"the old file is there until close()");
			}
			assertArrayEquals(bytes("new"), SftpRegressionTest.read(jsch.createFileSource(d+"/x.txt")));
			assertEquals(List.of("x.txt"), names(f, d), "no temporary file left");
			assertEquals(3, x.length());
		} finally {
			f.disConnect();
		}
	}

	/** If putting the new file in place fails, the old one is untouched and the temporary file is removed. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void failedSafeOverwriteLeavesTheOldFile(String impl) throws IOException {
		String d = freshDir(impl, "safe-fail");
		SftpFileSourceFactory f = safe(impl);
		try {
			SftpRegressionTest.write(f.createFileSource(d+"/x.txt"), bytes("old contents"));
			OutputStream out = f.createFileSource(d+"/x.txt").getOutputStream();
			out.write(bytes("new"));
			List<String> during = names(f, d);
			assertEquals(2, during.size(), "the target and the temporary file: "+during);
			String temp = during.get(0).equals("x.txt") ? during.get(1) : during.get(0);
			assertTrue(temp.startsWith(".x.txt.") && temp.endsWith(".tmp"), temp);
			jsch.createFileSource(d+"/"+temp).delete();   // pulled away, so the rename fails

			assertThrows(IOException.class, out::close);
			assertArrayEquals(bytes("old contents"), SftpRegressionTest.read(jsch.createFileSource(d+"/x.txt")));
			assertEquals(List.of("x.txt"), names(f, d));
		} finally {
			f.disConnect();
		}
	}

	/**
	 * When the rename can't be done (here the target is a directory), the
	 * temporary file, still there, is removed rather than left behind.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void failedReplaceRemovesTheTemporaryFile(String impl) throws IOException {
		String d = freshDir(impl, "safe-dir");
		SftpFileSourceFactory f = safe(impl);
		try {
			assertTrue(f.createFileSource(d+"/sub").mkdir());
			SftpRegressionTest.write(f.createFileSource(d+"/sub/inside.txt"), bytes("inside"));
			OutputStream out = f.createFileSource(d+"/sub").getOutputStream();
			out.write(bytes("data"));
			assertEquals(2, names(f, d).size(), "the directory and the temporary file");
			assertThrows(IOException.class, out::close);
			assertEquals(List.of("sub"), names(f, d), "no temporary file left");
			assertTrue(jsch.createFileSource(d+"/sub").isDirectory());
			assertEquals(List.of("inside.txt"), names(f, d+"/sub"));
		} finally {
			f.disConnect();
		}
	}

	/** A new file and an append work as usual with safeOverwrite on. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void safeOverwriteForNewFilesAndAppends(String impl) throws IOException {
		String d = freshDir(impl, "safe-new");
		SftpFileSourceFactory f = safe(impl);
		try {
			SftpRegressionTest.write(f.createFileSource(d+"/new.txt"), bytes("hello"));
			try (OutputStream out = f.createFileSource(d+"/new.txt").getOutputStream(true)) {
				out.write(bytes(" world"));
			}
			assertArrayEquals(bytes("hello world"), SftpRegressionTest.read(jsch.createFileSource(d+"/new.txt")));
			assertEquals(List.of("new.txt"), names(f, d));
		} finally {
			f.disConnect();
		}
	}

	/** The new file keeps the old one's permission bits. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void safeOverwriteKeepsPermissions(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		String d = freshDir(impl, "safe-mode");
		SftpFileSourceFactory f = safe(impl);
		try {
			SftpRegressionTest.write(f.createFileSource(d+"/x.txt"), bytes("old"));
			SftpRegressionTest.chmod(f, d+"/x.txt", 0640);
			SftpRegressionTest.write(f.createFileSource(d+"/x.txt"), bytes("new"));
			assertEquals(0640, SftpRegressionTest.mode(f, d+"/x.txt"));
		} finally {
			f.disConnect();
		}
	}

	/** Writing through a symbolic link replaces the file it points to; the link stays a link. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void safeOverwriteThroughALink(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		String d = freshDir(impl, "safe-link");
		SftpFileSourceFactory f = safe(impl);
		try {
			FileSource real = SftpRegressionTest.write(f.createFileSource(d+"/real.txt"), bytes("old"));
			FileSource link = f.createSymbolicLink(f.createFileSource(d+"/link.txt"), real);
			SftpRegressionTest.write(f.createFileSource(d+"/link.txt"), bytes("new"));
			assertTrue(f.lstat(d+"/link.txt").isLink(), "still a link");
			assertArrayEquals(bytes("new"), SftpRegressionTest.read(jsch.createFileSource(d+"/real.txt")));
			assertArrayEquals(bytes("new"), SftpRegressionTest.read(link));
		} finally {
			f.disConnect();
		}
	}

	/** The channel call underneath: rename that replaces an existing file. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void replaceRenamesOverAnExistingFile(String impl) throws IOException {
		String d = freshDir(impl, "replace");
		SftpFileSourceFactory f = factory(impl);
		SftpRegressionTest.write(f.createFileSource(d+"/from.txt"), bytes("from"));
		SftpRegressionTest.write(f.createFileSource(d+"/to.txt"), bytes("to"));
		try (SftpChannel c = f.openSftp()) {
			c.replace(d+"/from.txt", d+"/to.txt");
			c.replace(d+"/to.txt", d+"/fresh.txt");   // nothing in the way
		}
		assertEquals(List.of("fresh.txt"), names(f, d));
		assertArrayEquals(bytes("from"), SftpRegressionTest.read(jsch.createFileSource(d+"/fresh.txt")));
	}

	@Test
	void safeOverwriteIsOffByDefaultAndSettable() {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		assertFalse(f.isSafeOverwrite());
		Properties p = f.getConnectProperties();
		assertEquals("false", p.getProperty(SftpFileSourceFactory.PROP_SAFE_OVERWRITE));
		p.setProperty(SftpFileSourceFactory.PROP_SAFE_OVERWRITE, "true");
		f.setConnectionProperties(p);
		assertTrue(f.isSafeOverwrite());
		assertTrue(((SftpFileSourceFactory) f.createThreadSafeCopy()).isSafeOverwrite());
	}

	@Test
	void tempNamesAreBesideTheTarget() {
		String t = SftpFileSource.tempPathFor("/home/me/x.txt");
		assertTrue(t.startsWith("/home/me/.x.txt.") && t.endsWith(".tmp"), t);
		assertTrue(SftpFileSource.tempPathFor("/x").startsWith("/.x."));
		assertTrue(SftpFileSource.tempPathFor("x").startsWith(".x."));
	}
}
