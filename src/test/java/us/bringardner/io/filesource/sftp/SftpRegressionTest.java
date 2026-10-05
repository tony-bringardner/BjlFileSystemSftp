package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.IRandomAccessStream;
import us.bringardner.io.filesource.sftp.client.SftpChannel;

/**
 * One test per bug fixed in the SFTP review (batches 1 to 5), so none of
 * them can come back unnoticed. Each test names the bug it guards against.
 * Tests that go through an SSH library run with both JSch and MINA.
 *
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer). Tests of links, Unix permissions, the remote
 * user and shell commands need OpenSSH and are skipped on the embedded
 * server.
 */
public class SftpRegressionTest {

	static final String DIR = "SftpRegressionTest";
	static final String USER = "unittest1";
	static final String PASSWORD = "0000";
	/** A reserved documentation address (TEST-NET-1): nothing answers there. */
	static final String UNREACHABLE = "192.0.2.1";

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
			FileSource dir = jsch.createFileSource(DIR);
			makeDeletable(dir);
			SftpRandomAccessTest.deleteAll(dir);
			jsch.disConnect();
		}
		if( mina != null ) {
			mina.disConnect();
		}
	}

	/** Tests may leave files without read/write permission; give them back. */
	static void makeDeletable(FileSource f) throws IOException {
		if( !f.exists()) {
			return;
		}
		f.setReadable(true, true);
		f.setWritable(true, true);
		if( f.isDirectory()) {
			f.setExecutable(true, true);
			for (FileSource k : f.listFiles()) {
				makeDeletable(k);
			}
		}
	}

	static SftpFileSourceFactory factory(String impl) {
		return impl.equals("jsch") ? jsch : mina;
	}

	static int port() {
		try {
			return TestServer.port();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static SftpFileSourceFactory newFactory(String impl, String password) {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		Properties p = f.getConnectProperties();
		p.setProperty("user", USER);
		p.setProperty("host", TestServer.HOST);
		p.setProperty("port", ""+port());
		p.setProperty("password", password);
		p.setProperty("implementation", impl);
		f.setConnectionProperties(p);
		return f;
	}

	/** A fresh, empty directory for one test; returns its absolute path. */
	static String freshDir(String impl, String name) throws IOException {
		FileSource d = jsch.createFileSource(DIR+"/"+impl+"-"+name);
		if( d.exists()) {
			makeDeletable(d);
			SftpRandomAccessTest.deleteAll(d);
		}
		assertTrue(d.mkdirs());
		return d.getAbsolutePath();
	}

	static FileSource write(FileSource f, byte[] data) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(data);
		}
		return f;
	}

	static byte[] read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return in.readAllBytes();
		}
	}

	/** Permission bits (including setuid/setgid/sticky) as the server reports them. */
	static int mode(SftpFileSourceFactory f, String path) throws IOException {
		return f.stat(path).getPermissions() & 07777;
	}

	static void chmod(SftpFileSourceFactory f, String path, int mode) throws IOException {
		f.sftp((SftpChannel c) -> { c.chmod(path, mode); return null; });
	}

	// ============================================================ batch 1

	/** (int)time/1000 overflowed, so every time set was garbage. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void modifiedAndAccessTimesRoundTrip(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "times");
		FileSource x = write(f.createFileSource(d+"/t.txt"), new byte[1]);
		long t = 1_790_596_800_000L;   // 2026-09-28 12:00 UTC, a whole second
		assertTrue(x.setLastModifiedTime(t));
		assertEquals(t, f.createFileSource(d+"/t.txt").lastModified());
		assertTrue(x.setLastAccessTime(t + 60_000));
		assertEquals(t + 60_000, f.createFileSource(d+"/t.txt").lastAccessTime(),
				"lastAccessTime() multiplied as int and overflowed");
	}

	/** Top-level paths were named "/" and children of the root got "//". */
	@Test
	void namesAndPathsNearTheRoot() throws IOException {
		assertEquals("home", jsch.createFileSource("/home").getName());
		assertEquals("/", jsch.createFileSource("/").getName());
		assertEquals("/etc", jsch.createFileSource("/").getChild("etc").getAbsolutePath());
		assertEquals(jsch.createFileSource("/etc"), jsch.createFileSource("/").getChild("etc"));
	}

	/** setHost("host:port") dropped the last character of the host. */
	@Test
	void setHostWithPort() {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		f.setHost("files.example.com:2222");
		assertEquals("files.example.com", f.getHost());
		assertEquals(2222, f.getPort());
	}

	/** setWritable(b, ownerOnly) had ownerOnly inverted. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void setWritableOwnerOnly(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "writable");
		FileSource x = write(f.createFileSource(d+"/w.txt"), new byte[1]);
		chmod(f, x.getAbsolutePath(), 0666);
		assertTrue(x.setWritable(false, true));
		assertEquals(0466, mode(f, x.getAbsolutePath()), "only the owner's bit changes");
		assertTrue(x.setWritable(true, false));
		assertEquals(0666, mode(f, x.getAbsolutePath()));
		assertTrue(x.setWritable(false, false));
		assertEquals(0444, mode(f, x.getAbsolutePath()), "everyone's bit changes");
	}

	/** equals() had no matching hashCode(); compareTo() used toString() and swallowed errors. */
	@Test
	void equalsHashCodeCompareTo() throws IOException {
		FileSource a1 = jsch.createFileSource("/tmp/a");
		FileSource a2 = jsch.createFileSource("/tmp").getChild("a");
		FileSource b = jsch.createFileSource("/tmp/b");
		assertEquals(a1, a2);
		assertEquals(a1.hashCode(), a2.hashCode());
		assertEquals(0, a1.compareTo(a2));
		assertTrue(a1.compareTo(b) < 0 && b.compareTo(a1) > 0, "a consistent order");
		assertThrows(NullPointerException.class, () -> a1.compareTo(null));
	}

	// ============================================================ batch 2

	/** Shrinking ran "truncate -s N path" in a shell with the path unquoted. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void shrinkingAnOddNameRunsNoShellCommand(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "odd");
		String marker = "PWNED-"+System.nanoTime();
		FileSource x = write(f.createFileSource(d+"/a b;touch "+marker), new byte[5000]);
		try (IRandomAccessStream r = x.getRandomAccessStream("rw")) {
			r.setLength(1000);
		}
		assertEquals(1000, f.createFileSource(x.getAbsolutePath()).length());
		assertFalse(f.createFileSource(f.getCurrentDirectory().getAbsolutePath()+"/"+marker).exists(),
				"a shell command ran in the home directory");
		assertEquals(1, f.createFileSource(d).listFiles().length, "no stray files like \"a\" or \"b\"");
	}

	/** Random access never closed its channel; the 10th open failed. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void randomAccessClosesItsChannel(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "leak");
		FileSource x = write(f.createFileSource(d+"/x.bin"), new byte[] {0, 1, 2, 3, 4, 5, 6});
		for (int i = 1; i <= 15; i++) {
			try (IRandomAccessStream r = x.getRandomAccessStream("r")) {
				r.seek(5);
				assertEquals(5, r.read(), "open number "+i);
			}
		}
	}

	/** Opening a file without read permission waited forever for a second reply. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void unreadableFileFailsInsteadOfHanging(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "noread");
		FileSource x = write(f.createFileSource(d+"/x.bin"), new byte[100]);
		chmod(f, x.getAbsolutePath(), 0);
		assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
			assertThrows(FileNotFoundException.class, () -> x.getRandomAccessStream("r"));
			assertThrows(FileNotFoundException.class, x::getSeekableInputStream);
			assertThrows(FileNotFoundException.class, x::getInputStream);
		});
	}

	/** A read-only file couldn't be opened for random access at all. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void readOnlyFileCanStillBeRead(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "ro");
		byte[] data = new byte[1000];
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte) i;
		}
		FileSource x = write(f.createFileSource(d+"/x.bin"), data);
		chmod(f, x.getAbsolutePath(), 0444);
		try (IRandomAccessStream r = x.getRandomAccessStream("r")) {
			r.seek(300);
			assertEquals(300 & 0xFF, r.read());
		}
		try (SftpRandomAccessIoController c = new SftpRandomAccessIoController(x)) {
			assertEquals(300 & 0xFF, c.read(300), "the older constructor falls back to read-only");
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	// ============================================================ batch 3

	/** A factory with the wrong password reused another factory's logged-in session. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void wrongPasswordIsRefusedEvenWithASessionOpen(String impl) throws IOException {
		assertTrue(factory(impl).isConnected(), "a good session is open");
		SftpFileSourceFactory bad = newFactory(impl, "wrong-password");
		assertThrows(IOException.class, bad::connect);
	}

	/** The first factory to disconnect closed the shared session under the others. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void disconnectingOneFactoryLeavesTheOthersWorking(String impl) throws IOException {
		String d = freshDir(impl, "shared");
		byte[] data = new byte[1 << 20];
		new java.util.Random(3).nextBytes(data);
		SftpFileSourceFactory a = newFactory(impl, PASSWORD);
		SftpFileSourceFactory b = newFactory(impl, PASSWORD);
		// A session of their own, so 'a' creates it and 'b' joins: the bug only
		// showed when the creator disconnected first.
		String key = "regression-shared-"+impl+"-"+System.nanoTime();
		a.setSessionKey(key);
		b.setSessionKey(key);
		a.connect();
		b.connect();
		try {
			FileSource x = write(b.createFileSource(d+"/big.bin"), data);
			try (InputStream in = x.getInputStream()) {
				byte[] first = new byte[1000];
				assertEquals(1000, in.readNBytes(first, 0, 1000));
				a.disConnect();   // b is part-way through a download
				byte[] rest = in.readAllBytes();
				assertEquals(data.length - 1000, rest.length, "the download carried on");
			}
			assertTrue(b.createFileSource(d).exists(), "b still works");
		} finally {
			a.disConnect();
			b.disConnect();
		}
	}

	/** Calls from several threads interleaved on one channel and could hang. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void manyThreadsOnOneFactory(String impl) throws Exception {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "threads");
		write(f.createFileSource(d+"/t.txt"), new byte[10]);
		AtomicInteger errors = new AtomicInteger();
		AtomicReference<Throwable> first = new AtomicReference<>();
		assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
			ExecutorService pool = Executors.newFixedThreadPool(8);
			List<Future<?>> jobs = new ArrayList<>();
			for (int t = 0; t < 8; t++) {
				final boolean on = t % 2 == 0;
				jobs.add(pool.submit(() -> {
					for (int i = 0; i < 50; i++) {
						try {
							FileSource x = f.createFileSource(d+"/t.txt");
							if( i % 2 == 0 ) {
								x.setGroupReadable(on);
							} else {
								x.refresh();
								x.exists();
								f.createFileSource(d).listFiles();
							}
						} catch (Throwable e) {
							errors.incrementAndGet();
							first.compareAndSet(null, e);
						}
					}
				}));
			}
			for (Future<?> j : jobs) {
				j.get();
			}
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		});
		assertEquals(0, errors.get(), "first error: "+first.get());
	}

	/** createThreadSafeCopy() shared the original's channel and lost settings. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void threadSafeCopyWorksAlongsideTheOriginal(String impl) throws Exception {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "copy");
		write(f.createFileSource(d+"/c.txt"), new byte[10]);
		SftpFileSourceFactory copy = (SftpFileSourceFactory) f.createThreadSafeCopy();
		assertEquals(f.getEffectiveImplementation(), copy.getEffectiveImplementation());
		assertEquals(f.getAttributeCacheTtl(), copy.getAttributeCacheTtl());
		copy.connect();
		try {
			assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
				ExecutorService pool = Executors.newFixedThreadPool(2);
				Future<?> one = pool.submit(() -> { for (int i = 0; i < 100; i++) f.createFileSource(d).listFiles(); return null; });
				Future<?> two = pool.submit(() -> { for (int i = 0; i < 100; i++) copy.createFileSource(d).listFiles(); return null; });
				one.get();
				two.get();
				pool.shutdown();
			});
		} finally {
			copy.disConnect();
		}
	}

	/** getURL() threw when disconnected and included the password. */
	@Test
	void urlWithoutConnectionOrPassword() {
		SftpFileSourceFactory f = newFactory("jsch", "secret-pw");
		String url = f.getURL();
		assertEquals("sftp://"+USER+"@localhost:"+port(), url);
		assertFalse(url.contains("secret-pw"));
		assertNotNull(f.getTitle());
	}

	/** runCommand read stdout then stderr, so a lot of stderr deadlocked it. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void runCommandWithLotsOfStderr(String impl) {
		TestServer.assumeOpenSsh();
		String out = assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
			factory(impl).runCommand("head -c 200000 /dev/zero | tr '\\0' x >&2; echo done"));
		assertTrue(out.startsWith("done"));
		assertEquals(200_005, out.length(), "stdout (\"done\\n\") then all of stderr");
	}

	/** An unreachable host blocked forever: there was no connect timeout. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void unreachableHostTimesOut(String impl) {
		SftpFileSourceFactory f = newFactory(impl, PASSWORD);
		f.setHost(UNREACHABLE);
		f.setConnectTimeout(2000);
		assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
			assertThrows(IOException.class, f::connect);
		});
	}

	/** Any host key was accepted; with checking on, an unknown server must be refused. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void strictHostKeyCheckingRefusesUnknownServers(String impl) throws IOException {
		Path empty = Files.createTempFile("known_hosts", "");
		try {
			SftpFileSourceFactory f = newFactory(impl, PASSWORD);
			f.setStrictHostKeyChecking("yes");
			f.setKnownHosts(empty.toString());
			assertThrows(IOException.class, f::connect);
		} finally {
			Files.deleteIfExists(empty);
		}
	}

	// ============================================================ batch 10

	/** Changing any one setting cleared the private key file and the key itself. */
	@Test
	void partialPropertiesKeepTheKey() {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		f.setPrivateKeyFileName("/home/me/.ssh/id_ed25519");
		f.setPrivateKey("KEY".getBytes());

		Properties only = new Properties();
		only.setProperty(SftpFileSourceFactory.PROP_CONNECT_TIMEOUT, "5000");
		f.setConnectionProperties(only);
		assertEquals(5000, f.getConnectTimeout());
		assertEquals("/home/me/.ssh/id_ed25519", f.getPrivateKeyFileName(), "kept when not given");
		assertArrayEquals("KEY".getBytes(), f.getPrivateKey(), "kept when not given");

		Properties clear = new Properties();
		clear.setProperty(SftpFileSourceFactory.PROP_PRIVATE_KEY_FILE_NAME, "");
		clear.setProperty(SftpFileSourceFactory.PROP_PRIVATE_KEY, "");
		f.setConnectionProperties(clear);
		assertEquals(null, f.getPrivateKeyFileName(), "an empty value clears it");
		assertEquals(null, f.getPrivateKey());
	}

	/** User and password in an sftp:// URL weren't percent-decoded. */
	@Test
	void urlUserAndPasswordAreDecoded() throws Exception {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		f.setConnectionProperties(new java.net.URL(null, "sftp://me%40corp:p%40ss%3Aw+rd@files.example.com:2222/x", new Handler()));
		assertEquals("me@corp", f.getUser());
		assertEquals("p@ss:w+rd", f.getPassword(), "'+' is not a space in a URL");
		assertEquals("files.example.com", f.getHost());
		assertEquals(2222, f.getPort());

		SftpFileSourceFactory g = new SftpFileSourceFactory();
		g.setConnectionProperties(new java.net.URL(null, "sftp://bob@example.com/", new Handler()));
		assertEquals("bob", g.getUser());
		assertEquals("example.com", g.getHost());
	}

	/** JSch's connection thread wasn't a daemon, so a program that forgot disConnect() never exited. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void libraryThreadsDoNotKeepTheProgramAlive(String impl) throws IOException {
		java.util.Set<Thread> before = Thread.getAllStackTraces().keySet();
		SftpFileSourceFactory f = newFactory(impl, PASSWORD);
		f.setSessionKey("daemon-check-"+impl+"-"+System.nanoTime());   // a session of its own
		f.connect();
		try {
			f.createFileSource(DIR).exists();
			List<String> nonDaemon = new ArrayList<>();
			for (Thread t : Thread.getAllStackTraces().keySet()) {
				if( !before.contains(t) && t.isAlive() && !t.isDaemon()) {
					nonDaemon.add(t.getName());
				}
			}
			assertEquals(List.of(), nonDaemon, "threads that would keep the JVM running");
		} finally {
			f.disConnect();
		}
	}

	// ============================================================ batch 4

	/** A link to a directory wasn't a directory and couldn't be listed. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void linkToADirectoryIsADirectory(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "links");
		FileSource real = f.createFileSource(d+"/real");
		assertTrue(real.mkdir());
		write(f.createFileSource(d+"/real/a.txt"), new byte[3]);
		FileSource link = f.createFileSource(d+"/link");
		f.createSymbolicLink(link, real);

		FileSource l = f.createFileSource(d+"/link");
		assertTrue(l.isDirectory());
		assertEquals(1, l.listFiles().length);
		for (FileSource k : f.createFileSource(d).listFiles()) {
			if( k.getName().equals("link")) {
				assertTrue(k.isDirectory(), "as seen in its parent's listing");
			}
		}
		assertEquals(real.getAbsolutePath(), l.getLinkedTo().getAbsolutePath());
	}

	/** A link whose target is missing said it existed. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void brokenLinkDoesNotExist(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "broken");
		f.createSymbolicLink(f.createFileSource(d+"/dangling"), f.createFileSource(d+"/missing"));
		assertFalse(f.createFileSource(d+"/dangling").exists());
	}

	/** A relative link target was resolved against the wrong directory. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void relativeLinkTarget(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "rel");
		assertTrue(f.createFileSource(d+"/sub").mkdir());
		write(f.createFileSource(d+"/sub/a.txt"), new byte[3]);
		f.runCommand("cd '"+d+"' && ln -s sub/a.txt rel-link");
		FileSource target = f.createFileSource(d+"/rel-link").getLinkedTo();
		assertEquals(d+"/sub/a.txt", target.getAbsolutePath());
	}

	/** delete() of a missing file threw; deleting a link must not touch its target. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void deleteSemantics(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "delete");
		assertFalse(f.createFileSource(d+"/nope.txt").delete(), "false, like java.io.File");

		FileSource real = f.createFileSource(d+"/real");
		assertTrue(real.mkdir());
		write(f.createFileSource(d+"/real/keep.txt"), new byte[3]);
		f.createSymbolicLink(f.createFileSource(d+"/link"), real);
		assertTrue(f.createFileSource(d+"/link").delete());
		assertFalse(f.createFileSource(d+"/link").exists());
		assertTrue(f.createFileSource(d+"/real/keep.txt").exists(), "the target is kept");
	}

	/** Permission setters dropped the setuid/setgid/sticky bits. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void permissionSettersKeepSpecialBits(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "sticky");
		FileSource dir = f.createFileSource(d+"/shared");
		assertTrue(dir.mkdir());
		chmod(f, dir.getAbsolutePath(), 01775);
		assertTrue(dir.setOtherWritable(true));
		assertEquals(01777, mode(f, dir.getAbsolutePath()), "sticky bit kept");
		assertTrue(dir.setOtherWritable(false));
		assertEquals(01775, mode(f, dir.getAbsolutePath()));
	}

	/** whoAmI() fell back to the local user; a directory's owner came from its first child. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void ownerAndIdentityAreRemote(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "owner");
		write(f.createFileSource(d+"/mine.txt"), new byte[1]);
		assertEquals(USER, f.whoAmI().getName());
		assertEquals(USER, f.createFileSource(d+"/mine.txt").getOwner().getName());
		assertEquals(USER, f.createFileSource(d).getOwner().getName());
	}

	/** canRead() and friends turned every error, even a lost connection, into false. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void permissionChecksReportConnectionErrors(String impl) throws IOException {
		SftpFileSourceFactory g = newFactory(impl, PASSWORD);
		g.connect();
		FileSource x = g.createFileSource(DIR);   // never asked about yet
		g.setAttributeCacheTtl(0);
		g.setHost(UNREACHABLE);
		g.setConnectTimeout(2000);
		g.disConnect();
		assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
			assertThrows(IOException.class, x::canRead);
		});
	}

	// ============================================================ batch 5

	/** MINA couldn't make hard links on an OpenSSH server (SFTP v3). */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void hardLink(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "hard");
		FileSource orig = write(f.createFileSource(d+"/orig.txt"), "hello".getBytes("UTF-8"));
		FileSource link = f.createLink(f.createFileSource(d+"/hard.txt"), orig);
		assertArrayEquals("hello".getBytes("UTF-8"), read(link));
		try (OutputStream out = orig.getOutputStream(true)) {
			out.write(" world".getBytes("UTF-8"));
		}
		assertEquals("hello world", new String(read(f.createFileSource(d+"/hard.txt")), "UTF-8"),
				"one file, two names");
	}

	/** Appending and reading from an offset, through each library's streams. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void appendAndReadFromOffset(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "append");
		FileSource x = write(f.createFileSource(d+"/x.txt"), "hello".getBytes("UTF-8"));
		try (OutputStream out = x.getOutputStream(true)) {
			out.write(" world".getBytes("UTF-8"));
		}
		assertEquals("hello world", new String(read(x), "UTF-8"));
		try (InputStream in = x.getInputStream(6)) {
			assertEquals("world", new String(in.readAllBytes(), "UTF-8"));
		}
	}

	/** Each factory really uses the library it was configured with. */
	@Test
	void implementationSelection() {
		assertEquals("jsch", jsch.getEffectiveImplementation());
		assertEquals("mina", mina.getEffectiveImplementation());
		assertThrows(IllegalArgumentException.class, () -> new SftpFileSourceFactory().setImplementation("telnet"));
		assertNotEquals(jsch.getSessionKey(), mina.getSessionKey(), "the two libraries never share a session");
	}
}
