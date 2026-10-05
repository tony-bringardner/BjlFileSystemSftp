package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;

/**
 * Directory listings are never stale, and a file's cached attributes live
 * for the factory's attribute cache time. "Someone else" is played by the
 * other SSH library's factory, so each change really comes from a different
 * object (and a different connection).
 *
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer).
 */
public class SftpCacheTest {

	static final String DIR = "SftpCacheTest";
	static SftpFileSourceFactory jsch;
	static SftpFileSourceFactory mina;

	@BeforeAll
	static void setUp() throws IOException {
		jsch = SftpRandomAccessTest.connect("jsch");
		mina = SftpRandomAccessTest.connect("mina");
		FileSource dir = jsch.createFileSource(DIR);
		if( !dir.exists()) {
			assertTrue(dir.mkdirs());
		}
	}

	@AfterAll
	static void tearDown() throws IOException {
		if( jsch != null ) {
			SftpRandomAccessTest.deleteAll(jsch.createFileSource(DIR));
			jsch.disConnect();
		}
		if( mina != null ) {
			mina.disConnect();
		}
	}

	static SftpFileSourceFactory mine(String impl) {
		return impl.equals("jsch") ? jsch : mina;
	}

	static SftpFileSourceFactory other(String impl) {
		return impl.equals("jsch") ? mina : jsch;
	}

	/** A fresh, empty directory for one test. */
	static String freshDir(String impl, String name) throws IOException {
		String path = DIR+"/"+impl+"-"+name;
		FileSource d = jsch.createFileSource(path);
		if( d.exists()) {
			SftpRandomAccessTest.deleteAll(d);
		}
		assertTrue(d.mkdirs());
		return d.getAbsolutePath();
	}

	static void create(FileSource f, int size) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(new byte[size]);
		}
	}

	static Set<String> names(FileSource dir) throws IOException {
		Set<String> ret = new TreeSet<>();
		for (FileSource f : dir.listFiles()) {
			ret.add(f.getName());
		}
		return ret;
	}

	// ------------------------------------------------------------ listings

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void listingSeesChangesMadeElsewhere(String impl) throws IOException {
		String d = freshDir(impl, "list");
		FileSource dir = mine(impl).createFileSource(d);
		assertEquals(Set.of(), names(dir));

		// through another object of the same factory
		create(mine(impl).createFileSource(d+"/a.txt"), 1);
		assertEquals(Set.of("a.txt"), names(dir));

		// through another connection ("another program")
		create(other(impl).createFileSource(d+"/b.txt"), 1);
		mine(impl).createFileSource(d+"/sub").mkdir();
		assertEquals(Set.of("a.txt", "b.txt", "sub"), names(dir));

		assertTrue(other(impl).createFileSource(d+"/a.txt").delete());
		assertTrue(other(impl).createFileSource(d+"/b.txt")
				.renameTo(other(impl).createFileSource(d+"/c.txt")));
		assertEquals(Set.of("c.txt", "sub"), names(dir));
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void listedChildrenCarryFreshAttributes(String impl) throws IOException {
		String d = freshDir(impl, "attrs");
		FileSource dir = mine(impl).createFileSource(d);
		create(other(impl).createFileSource(d+"/f.bin"), 100);
		FileSource f1 = dir.listFiles()[0];
		assertEquals(100, f1.length());

		create(other(impl).createFileSource(d+"/f.bin"), 5000);
		FileSource f2 = dir.listFiles()[0];
		assertEquals(5000, f2.length(), "a new listing has the new size");
		assertTrue(f2.isFile());
	}

	// ------------------------------------------------------------ attribute cache time

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void ttlZeroAlwaysAsksTheServer(String impl) throws IOException {
		SftpFileSourceFactory f = mine(impl);
		long old = f.getAttributeCacheTtl();
		f.setAttributeCacheTtl(0);
		try {
			String d = freshDir(impl, "ttl0");
			FileSource x = f.createFileSource(d+"/x.bin");
			create(x, 10);
			assertTrue(x.exists());
			assertEquals(10, x.length());

			create(other(impl).createFileSource(d+"/x.bin"), 70);
			assertEquals(70, x.length(), "new size seen at once");

			assertTrue(other(impl).createFileSource(d+"/x.bin").delete());
			assertFalse(x.exists(), "deletion seen at once");
		} finally {
			f.setAttributeCacheTtl(old);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void cachedUntilTheTimeRunsOut(String impl) throws Exception {
		SftpFileSourceFactory f = mine(impl);
		long old = f.getAttributeCacheTtl();
		f.setAttributeCacheTtl(600);
		try {
			String d = freshDir(impl, "ttl");
			FileSource x = f.createFileSource(d+"/x.bin");
			create(x, 10);
			assertEquals(10, x.length());   // fills the cache

			assertTrue(other(impl).createFileSource(d+"/x.bin").delete());
			assertTrue(x.exists(), "still cached within the time limit");
			Thread.sleep(900);
			assertFalse(x.exists(), "asks again once the time has passed");
		} finally {
			f.setAttributeCacheTtl(old);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void negativeKeepsUntilRefresh(String impl) throws Exception {
		SftpFileSourceFactory f = mine(impl);
		long old = f.getAttributeCacheTtl();
		f.setAttributeCacheTtl(-1);
		try {
			String d = freshDir(impl, "neg");
			FileSource x = f.createFileSource(d+"/x.bin");
			create(x, 10);
			assertTrue(x.exists());

			assertTrue(other(impl).createFileSource(d+"/x.bin").delete());
			Thread.sleep(300);
			assertTrue(x.exists(), "kept, however long");
			x.refresh();
			assertFalse(x.exists(), "until refresh()");
		} finally {
			f.setAttributeCacheTtl(old);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void ownChangesAreSeenWhateverTheTime(String impl) throws IOException {
		SftpFileSourceFactory f = mine(impl);
		long old = f.getAttributeCacheTtl();
		f.setAttributeCacheTtl(-1);
		try {
			String d = freshDir(impl, "own");
			FileSource x = f.createFileSource(d+"/x.bin");
			assertFalse(x.exists());
			create(x, 10);
			assertTrue(x.exists());
			assertEquals(10, x.length());
			try (OutputStream out = x.getOutputStream(true)) {
				out.write(new byte[5]);
			}
			assertEquals(15, x.length());
			assertTrue(x.delete());
			assertFalse(x.exists());
		} finally {
			f.setAttributeCacheTtl(old);
		}
	}

	// ------------------------------------------------------------ configuration

	@Test
	void timeIsConfigurable() throws IOException {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		assertEquals(SftpFileSourceFactory.DEFAULT_ATTRIBUTE_CACHE_TTL, f.getAttributeCacheTtl(), "default");

		f.setAttributeCacheTtl(1234);
		assertEquals(1234, f.getAttributeCacheTtl(), "setter");
		assertEquals("1234", f.getConnectProperties().getProperty(SftpFileSourceFactory.PROP_ATTRIBUTE_CACHE_TTL),
				"reported in the connection properties");

		Properties p = f.getConnectProperties();
		p.setProperty(SftpFileSourceFactory.PROP_ATTRIBUTE_CACHE_TTL, "0");
		f.setConnectionProperties(p);
		assertEquals(0, f.getAttributeCacheTtl(), "connection property");

		f.setAttributeCacheTtl(-1);
		assertEquals(-1, ((SftpFileSourceFactory) f.createThreadSafeCopy()).getAttributeCacheTtl(),
				"copied by createThreadSafeCopy");

		Properties noTtl = new Properties();
		noTtl.setProperty(SftpFileSourceFactory.PROP_HOST, "localhost");
		f.setConnectionProperties(noTtl);
		assertEquals(-1, f.getAttributeCacheTtl(), "left alone when the property isn't given");

		String key = SftpFileSourceFactory.SYSTEM_PROPERTY_ATTRIBUTE_CACHE_TTL;
		String saved = System.getProperty(key);
		try {
			System.setProperty(key, "250");
			assertEquals(250, new SftpFileSourceFactory().getAttributeCacheTtl(), "system property default");
			System.setProperty(key, "not a number");
			assertEquals(SftpFileSourceFactory.DEFAULT_ATTRIBUTE_CACHE_TTL,
					new SftpFileSourceFactory().getAttributeCacheTtl(), "bad value ignored");
		} finally {
			if( saved == null ) {
				System.clearProperty(key);
			} else {
				System.setProperty(key, saved);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void listFilesOfAFileIsNull(String impl) throws IOException {
		String d = freshDir(impl, "file");
		FileSource x = mine(impl).createFileSource(d+"/x.bin");
		create(x, 1);
		assertEquals(null, x.listFiles() == null ? null : Arrays.asList(x.listFiles()));
	}
}
