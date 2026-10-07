package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceUser;
import us.bringardner.io.filesource.IRandomAccessStream;
import us.bringardner.io.filesource.ISeekableInputStream;
import us.bringardner.io.filesource.sftp.client.SftpAttributes;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpEntry;
import us.bringardner.io.filesource.sftp.client.SftpFile;
import us.bringardner.io.filesource.sftp.client.jsch.JschTestAccess;

/**
 * Batch 17: a time limit that holds for JSch commands, fewer round trips,
 * bigger chunks, owner names and the remote user looked up once, MINA
 * noticing a dead connection, and read-only calls retried after a dropped
 * channel. Tests that go through an SSH library run with both.
 * <p>
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer). The shell command tests need OpenSSH.
 */
public class SftpBatch17Test {

	static final String DIR = "SftpBatch17Test";

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

	static byte[] random(int size, long seed) {
		byte[] b = new byte[size];
		new Random(seed).nextBytes(b);
		return b;
	}

	/**
	 * Counts the server calls that SftpFileSource makes through the factory.
	 * With 'hideLongNames', listings come back without the ls-style long
	 * names, as from a server that doesn't send them.
	 */
	static class CountingFactory extends SftpFileSourceFactory {
		private static final long serialVersionUID = 1L;
		final AtomicInteger stats = new AtomicInteger();
		final AtomicInteger lists = new AtomicInteger();
		final AtomicInteger commands;
		boolean hideLongNames;

		CountingFactory(String impl, AtomicInteger commands) throws IOException {
			this.commands = commands;
			setConnectionProperties(TestServer.properties(impl, TestServer.port()));
			assertTrue(connect());
		}

		@Override
		public SftpAttributes stat(String path) throws IOException {
			stats.incrementAndGet();
			return super.stat(path);
		}

		@Override
		public List<SftpEntry> ls(String path) throws IOException {
			lists.incrementAndGet();
			if( !hideLongNames ) {
				return super.ls(path);
			}
			List<SftpEntry> ret = new ArrayList<>();
			for (SftpEntry e : sftp(c -> c.list(path))) {
				ret.add(new SftpEntry(e.getFilename(), null, e.getAttrs()));
			}
			return ret;
		}

		@Override
		public String runCommand(String command) throws IOException {
			commands.incrementAndGet();
			return super.runCommand(command);
		}
	}

	// ------------------------------------------------------------ #7 commands

	/** JSch read the output until EOF before checking the time, so a hung command blocked forever. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void commandTimeLimitHoldsWhileOutputIsOpen(String impl) {
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		long start = System.currentTimeMillis();
		assertTimeoutPreemptively(Duration.ofSeconds(15),
				() -> assertThrows(IOException.class, () -> f.runCommand("sleep 60", 1000)));
		assertTrue(System.currentTimeMillis() - start < 10_000);
	}

	/** Reading only what has arrived still collects all of a command's output. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void commandOutputIsComplete(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		assertEquals("hello\n", f.runCommand("echo hello"));
		String out = f.runCommand("seq 1 200000");   // about 1.2 MB, many times the pipe's buffer
		String[] lines = out.split("\n");
		assertEquals(200000, lines.length);
		assertEquals("200000", lines[lines.length - 1]);
		assertThrows(IOException.class, () -> f.runCommand("exit 3"));
	}

	// ------------------------------------------------------------ #14 round trips

	/** JSch's open() did a stat that the read it opens anyway makes unnecessary. */
	@Test
	void jschOpenSendsNoStat() throws IOException {
		String d = freshDir("jsch", "open");
		SftpRegressionTest.write(jsch.createFileSource(d+"/x.bin"), new byte[] {1, 2, 3});
		AtomicInteger stats = new AtomicInteger();
		SftpChannel raw = jsch.getConnection().openSftp();
		try {
			SftpChannel counting = JschTestAccess.countingStats(raw, stats);
			try (SftpFile file = counting.open(d+"/x.bin", false)) {
				byte[] b = new byte[3];
				assertEquals(3, file.read(0, b, 0, 3));
				assertArrayEquals(new byte[] {1, 2, 3}, b);
			}
			assertEquals(0, stats.get(), "stat calls");
			assertThrows(NoSuchFileException.class, () -> counting.open(d+"/missing.bin", false));
		} finally {
			raw.close();
		}
	}

	/** Opening a stream to read used to throw the cached attributes away, costing a stat afterwards. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void readingKeepsCachedAttributes(String impl) throws IOException {
		String d = freshDir(impl, "keep-attrs");
		SftpRegressionTest.write(factory(impl).createFileSource(d+"/x.txt"), "hello".getBytes("UTF-8"));
		CountingFactory f = new CountingFactory(impl, new AtomicInteger());
		try {
			f.setAttributeCacheTtl(60_000);
			FileSource x = f.createFileSource(d+"/x.txt");
			assertEquals(5, x.length());
			try (InputStream in = x.getInputStream()) {
				assertEquals(5, in.readAllBytes().length);
			}
			assertEquals(5, x.length());
			assertEquals(1, f.stats.get(), "stat calls");
		} finally {
			f.disConnect();
		}
	}

	// ------------------------------------------------------------ #15 chunk size

	@Test
	void defaultChunkIs128K() {
		assertEquals(128 * 1024, SftpFileSourceFactory.DEFAULT_CHUNK_SIZE);
		assertEquals(128 * 1024, new SftpFileSourceFactory().getChunkSize());
	}

	/** Several default-size chunks, written and read back through random access and a seekable stream. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void defaultChunksRoundTrip(String impl) throws Exception {
		SftpFileSourceFactory f = factory(impl);
		assertEquals(SftpFileSourceFactory.DEFAULT_CHUNK_SIZE, f.getChunkSize());
		String d = freshDir(impl, "chunks");
		byte[] data = random(3 * SftpFileSourceFactory.DEFAULT_CHUNK_SIZE + 1234, 17);
		FileSource x = f.createFileSource(d+"/x.bin");
		try (IRandomAccessStream r = x.getRandomAccessStream("rw")) {
			r.write(data);
		}
		byte[] back = new byte[data.length];
		try (IRandomAccessStream r = f.createFileSource(d+"/x.bin").getRandomAccessStream("r")) {
			assertEquals(data.length, r.length());
			r.readFully(back);
		}
		assertArrayEquals(data, back);
		ISeekableInputStream s = f.createFileSource(d+"/x.bin").getSeekableInputStream();
		try {
			s.seek(SftpFileSourceFactory.DEFAULT_CHUNK_SIZE - 10);
			byte[] part = new byte[100];
			int got = 0;
			while( got < part.length ) {
				got += s.read(part, got, part.length - got);
			}
			assertArrayEquals(java.util.Arrays.copyOfRange(data, SftpFileSourceFactory.DEFAULT_CHUNK_SIZE - 10,
					SftpFileSourceFactory.DEFAULT_CHUNK_SIZE + 90), part);
		} finally {
			s.close();
		}
	}

	// ------------------------------------------------------------ #16 owner names

	/** An owner no listing could name made every file with that owner list its directory again. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void unknownOwnerIsLookedUpOnce(String impl) throws IOException {
		String d = freshDir(impl, "owner");
		SftpRegressionTest.write(factory(impl).createFileSource(d+"/a.txt"), new byte[1]);
		SftpRegressionTest.write(factory(impl).createFileSource(d+"/b.txt"), new byte[1]);
		CountingFactory f = new CountingFactory(impl, new AtomicInteger());
		try {
			f.hideLongNames = true;
			FileSourceUser a = (FileSourceUser) f.createFileSource(d+"/a.txt").getOwner();
			FileSourceUser b = (FileSourceUser) f.createFileSource(d+"/b.txt").getOwner();
			assertEquals(1, f.lists.get(), "directory listings");
			assertEquals(""+a.getId(), a.getName(), "the number stands in for the name");
			assertEquals(a.getId(), b.getId());

			// a listing that shows the names replaces the numbers
			f.hideLongNames = false;
			f.createFileSource(d).listFiles();
			FileSourceUser c = (FileSourceUser) f.createFileSource(d+"/a.txt").getOwner();
			assertEquals(jsch.createFileSource(d+"/a.txt").getOwner().getName(), c.getName());
		} finally {
			f.disConnect();
		}
	}

	// ------------------------------------------------------------ #17 remote user

	/** Every factory on a connection ran "id" for itself; now the connection remembers the answer. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void remoteUserIsWorkedOutOncePerConnection(String impl) throws IOException {
		AtomicInteger commands = new AtomicInteger();
		CountingFactory f1 = new CountingFactory(impl, commands);
		CountingFactory f2 = new CountingFactory(impl, commands);
		try {
			assertEquals(f1.getSessionKey(), f2.getSessionKey(), "the two share a connection");
			FileSourceUser u1 = f1.whoAmI();
			FileSourceUser u2 = f2.whoAmI();
			assertEquals(u1.getId(), u2.getId());
			assertTrue(commands.get() <= 1, "\"id\" ran "+commands.get()+" times");
		} finally {
			f1.disConnect();
			f2.disConnect();
		}
	}

	// ------------------------------------------------------------ #8 dead connections

	/**
	 * A connection whose peer has gone silent is dropped after a few missed
	 * keepalives. MINA's keepalives used to expect no reply, so it stayed
	 * "connected".
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void silentConnectionIsDropped(String impl) throws Exception {
		try (SftpReadAheadTest.DelayProxy proxy = new SftpReadAheadTest.DelayProxy(TestServer.port(), 0)) {
			SftpFileSourceFactory f = new SftpFileSourceFactory();
			Properties p = TestServer.properties(impl, proxy.port());
			p.setProperty(SftpFileSourceFactory.PROP_SERVER_ALIVE_INTERVAL, "300");
			f.setConnectionProperties(p);
			assertTrue(f.connect());
			try {
				assertTrue(f.isConnected());
				proxy.freeze();
				long start = System.currentTimeMillis();
				while( f.isConnected() && System.currentTimeMillis() - start < 10_000 ) {
					Thread.sleep(50);
				}
				assertFalse(f.isConnected(), "still connected after "+(System.currentTimeMillis() - start)+" ms");
			} finally {
				f.disConnect();
			}
		}
	}

	// ------------------------------------------------------------ #6 retry

	/**
	 * Makes the next channel the factory's pool hands out one whose first
	 * call to 'method' closes the real channel and fails, as when it drops
	 * mid-call. (Batch 19: calls borrow from the pool; they used to use the
	 * factory's own channel, which this replaced.)
	 */
	static void breakChannelOn(SftpFileSourceFactory f, String method) throws Exception {
		SftpChannel real = f.getConnection().openSftp();
		AtomicBoolean failed = new AtomicBoolean();
		Object broken = Proxy.newProxyInstance(SftpChannel.class.getClassLoader(), new Class<?>[] {SftpChannel.class},
				(proxy, m, args) -> {
					if( m.getName().equals("isOpen")) {
						return !failed.get() && real.isOpen();
					}
					if( m.getName().equals(method) && failed.compareAndSet(false, true)) {
						real.close();
						throw new IOException("connection lost (test)");
					}
					try {
						return m.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		f.channelPool().addIdleForTest((SftpChannel) broken);
	}

	/** A read-only call whose channel drops is tried again on a new channel. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void readOnlyCallIsRetriedAfterADroppedChannel(String impl) throws Exception {
		String d = freshDir(impl, "retry");
		SftpRegressionTest.write(jsch.createFileSource(d+"/x.txt"), "hello".getBytes("UTF-8"));
		SftpFileSourceFactory f = TestServer.connect(impl);
		try {
			breakChannelOn(f, "stat");
			assertEquals(5, f.stat(d+"/x.txt").getSize());
			breakChannelOn(f, "list");
			assertEquals(1, f.createFileSource(d).listFiles().length);
		} finally {
			f.disConnect();
		}
	}

	/** A change isn't retried: the first attempt may have reached the server. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void changeIsNotRetried(String impl) throws Exception {
		String d = freshDir(impl, "no-retry");
		SftpFileSourceFactory f = TestServer.connect(impl);
		try {
			breakChannelOn(f, "mkdir");
			IOException e = assertThrows(IOException.class, () -> f.createFileSource(d+"/sub").mkdir());
			assertEquals("connection lost (test)", e.getMessage());
			assertFalse(jsch.createFileSource(d+"/sub").exists());
			assertTrue(f.createFileSource(d+"/sub").mkdir(), "the next call reconnects");
		} finally {
			f.disConnect();
		}
	}
}
