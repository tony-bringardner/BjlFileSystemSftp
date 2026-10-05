package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.IRandomAccessStream;
import us.bringardner.io.filesource.ISeekableInputStream;
import us.bringardner.io.filesource.sftp.client.SftpChannel;

/**
 * The pool of idle SFTP channels (batch 13): streams reuse channels instead
 * of opening one each, a channel is never shared, a failed one is never
 * reused, and disconnecting closes the idle ones. Each test runs with both
 * SSH libraries.
 * <p>
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer). The tests of
 * permission denied and OpenSSH's channel limit need OpenSSH.
 */
public class SftpChannelPoolTest {

	static final String DIR = "SftpChannelPoolTest";
	static SftpFileSourceFactory jsch;
	static SftpFileSourceFactory mina;

	static SftpFileSourceFactory connect(String implementation) throws IOException {
		return TestServer.connect(implementation);
	}

	@BeforeAll
	static void setUp() throws IOException {
		jsch = connect("jsch");
		mina = connect("mina");
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
		if( jsch != null ) {
			SftpRandomAccessTest.deleteAll(jsch.createFileSource(DIR));
			jsch.disConnect();
		}
	}

	static SftpFileSourceFactory factory(String implementation) {
		return implementation.equals("jsch") ? jsch : mina;
	}

	static FileSource file(SftpFileSourceFactory f, String name) throws IOException {
		return f.createFileSource(DIR).getChild(f.getEffectiveImplementation()+"-"+name);
	}

	static void write(FileSource f, byte[] data) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(data);
		}
	}

	static byte[] read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return in.readAllBytes();
		}
	}

	static byte[] random(int size, long seed) {
		byte[] b = new byte[size];
		new Random(seed).nextBytes(b);
		return b;
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void fiftyStreamsInARowOpenFewChannels(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		FileSource file = file(f, "fifty.txt");
		write(file, "warm up".getBytes("UTF-8"));
		SftpChannelPool pool = f.channelPool();
		int before = pool.openedCount();
		for (int i = 0; i < 50; i++) {
			byte[] data = ("stream "+i).getBytes("UTF-8");
			switch (i % 5) {
			case 0:
				write(file, data);
				assertArrayEquals(data, read(file));
				break;
			case 1:
				try (IRandomAccessStream r = file.getRandomAccessStream("rw")) {
					r.setLength(0);
					r.write(data);
				}
				assertArrayEquals(data, read(file));
				break;
			case 2:
				write(file, data);
				ISeekableInputStream s = file.getSeekableInputStream();
				try {
					byte[] back = new byte[data.length];
					assertEquals(data.length, s.read(back, 0, back.length));
					assertArrayEquals(data, back);
				} finally {
					s.close();
				}
				break;
			case 3:
				write(file, data);
				try (InputStream in = file.getInputStream(1)) {
					assertEquals(data[1], (byte) in.read());
				}
				break;
			default:
				write(file, data);
				try (OutputStream out = file.getOutputStream(true)) {
					out.write('!');
				}
				assertEquals("stream "+i+"!", new String(read(file), "UTF-8"));
			}
		}
		int opened = pool.openedCount() - before;
		assertTrue(opened <= 2, "about 100 streams opened "+opened+" channels");
		assertTrue(pool.idleChannels().size() <= SftpChannelPool.MAX_IDLE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void fifteenStreamsInARowStayUnderTheChannelLimit(String impl) throws IOException {
		// OpenSSH allows 10 channels per connection by default
		SftpFileSourceFactory f = factory(impl);
		FileSource file = file(f, "fifteen.bin");
		byte[] data = random(100_000, 1);
		write(file, data);
		for (int i = 0; i < 15; i++) {
			assertArrayEquals(data, read(file), "stream "+i);
			try (IRandomAccessStream r = file.getRandomAccessStream("r")) {
				r.seek(50_000);
				assertEquals(data[50_000] & 0xFF, r.read());
			}
		}
		assertTrue(f.channelPool().idleChannels().size() <= SftpChannelPool.MAX_IDLE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void keepsAtMostMaxIdleChannels(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		FileSource file = file(f, "six.txt");
		write(file, "six at once".getBytes("UTF-8"));
		List<InputStream> open = new ArrayList<>();
		try {
			for (int i = 0; i < SftpChannelPool.MAX_IDLE + 2; i++) {
				open.add(file.getInputStream());   // each holds a channel
			}
			assertTrue(f.channelPool().idleChannels().isEmpty(), "all in use");
		} finally {
			for (InputStream in : open) {
				in.close();
			}
		}
		assertEquals(SftpChannelPool.MAX_IDLE, f.channelPool().idleChannels().size(), "the extra ones are closed");
		for (SftpChannel c : f.channelPool().idleChannels()) {
			assertTrue(c.isOpen());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void aBorrowedChannelLeavesThePool(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		SftpChannelPool pool = f.channelPool();
		read(file(f, "borrow.txt"), "fill the pool");
		int idle = pool.idleChannels().size();
		assertTrue(idle > 0);
		int opened = pool.openedCount();
		List<SftpChannel> held = new ArrayList<>();
		try {
			for (int i = 0; i <= idle; i++) {
				held.add(f.openSftp());
				assertEquals(idle - Math.min(i + 1, idle), pool.idleChannels().size(), "borrow "+i);
			}
			assertEquals(opened + 1, pool.openedCount(), "one more than were idle");
		} finally {
			for (SftpChannel c : held) {
				c.close();
			}
		}
		assertEquals(Math.min(idle + 1, SftpChannelPool.MAX_IDLE), pool.idleChannels().size());
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void closeThenOpenAtTheChannelLimit(String impl) throws IOException {
		TestServer.assumeOpenSsh();
		// OpenSSH's default limit is 10: the pool's channels and these
		// (opened past the pool, so its limit doesn't apply)
		SftpFileSourceFactory f = factory(impl);
		int room = 10 - f.channelPool().openCount();
		List<SftpChannel> held = new ArrayList<>();
		try {
			for (int i = 0; i < room; i++) {
				held.add(f.getConnection().openSftp());
			}
			for (int i = 0; i < 200; i++) {
				held.remove(0).close();
				held.add(f.getConnection().openSftp());   // refused if the close isn't finished
			}
		} finally {
			for (SftpChannel c : held) {
				c.close();
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void eightThreadsAtOnceGetTheirOwnData(String impl) throws Exception {
		SftpFileSourceFactory f = factory(impl);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<?>> results = new ArrayList<>();
			for (int t = 0; t < 8; t++) {
				final int thread = t;
				results.add(pool.submit(() -> {
					FileSource file = file(f, "thread-"+thread+".bin");
					for (int i = 0; i < 10; i++) {
						byte[] data = random(20_000 + thread * 1000 + i, thread * 100 + i);
						write(file, data);
						assertArrayEquals(data, read(file), "thread "+thread+" pass "+i);
						try (IRandomAccessStream r = file.getRandomAccessStream("rw")) {
							r.seek(10);
							r.write(thread);
						}
						data[10] = (byte) thread;
						ISeekableInputStream s = file.getSeekableInputStream();
						try {
							byte[] back = new byte[data.length];
							int got = 0;
							int n;
							while( got < back.length && (n = s.read(back, got, back.length - got)) > 0 ) {
								got += n;
							}
							assertArrayEquals(data, back, "thread "+thread+" pass "+i+" after the patch");
						} finally {
							s.close();
						}
					}
					return null;
				}));
			}
			for (Future<?> r : results) {
				r.get(120, TimeUnit.SECONDS);   // rethrows a thread's failure
			}
		} finally {
			pool.shutdownNow();
		}
		assertTrue(f.channelPool().idleChannels().size() <= SftpChannelPool.MAX_IDLE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void aRefusedOpenKeepsItsChannel(String impl) throws IOException {
		// Batch 19: "permission denied" and "no such file" are the server's
		// answer to the open, so nothing is left pending and the channel goes
		// back to the pool. (Before, any exception closed it; with calls on
		// pooled channels too, every exists() on a missing file would then
		// have cost a new channel.)
		TestServer.assumeOpenSsh();
		SftpFileSourceFactory f = factory(impl);
		FileSource locked = file(f, "locked.txt");
		write(locked, "secret".getBytes("UTF-8"));
		f.sftp(c -> { c.chmod(locked.getAbsolutePath(), 0); return null; });
		FileSource missing = file(f, "missing.txt");
		missing.delete();
		try {
			SftpChannelPool pool = f.channelPool();
			read(file(f, "ok.txt"), "warm up");
			List<SftpChannel> before = pool.idleChannels();
			assertFalse(before.isEmpty());
			int opened = pool.openedCount();

			assertThrows(FileNotFoundException.class, locked::getInputStream, "permission denied");
			assertThrows(FileNotFoundException.class, () -> missing.getRandomAccessStream("r"));
			assertThrows(FileNotFoundException.class, missing::getSeekableInputStream);
			assertThrows(FileNotFoundException.class, missing::getInputStream);

			assertEquals(before.size(), pool.idleChannels().size(), "every channel is back");
			assertTrue(pool.idleChannels().contains(before.get(0)), "the one they used too");
			assertTrue(before.get(0).isOpen());
			assertEquals(opened, pool.openedCount(), "no new channels");
		} finally {
			f.sftp(c -> { c.chmod(locked.getAbsolutePath(), 0644); return null; });
		}
	}


	/** Writes and reads back a small file, which leaves a channel in the pool. */
	static void read(FileSource f, String text) throws IOException {
		write(f, text.getBytes("UTF-8"));
		assertEquals(text, new String(read(f), "UTF-8"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void aStreamLeftOpenKeepsItsChannelOutOfThePool(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		SftpChannelPool pool = f.channelPool();
		SftpChannel ch = f.openSftp();
		FileSource file = file(f, "left-open.txt");
		write(file, "data".getBytes("UTF-8"));
		int idle = pool.idleChannels().size();
		InputStream in = ch.read(file.getAbsolutePath(), 0);
		ch.close();   // closed before its stream
		assertEquals(idle, pool.idleChannels().size(), "not given back with a handle open");
		assertThrows(IOException.class, () -> ch.stat(file.getAbsolutePath()), "unusable once closed");
		try {
			in.close();
		} catch (IOException e) {
			// the channel under it is gone; that's fine
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void disconnectClosesTheIdleChannels(String impl) throws IOException {
		SftpFileSourceFactory f = connect(impl);
		// its own session: a different session key from the shared factories
		f.setSessionKey("SftpChannelPoolTest-disconnect-"+impl);
		f.disConnect();
		assertTrue(f.connect());
		SftpChannelPool pool = f.channelPool();
		FileSource file = file(f, "disconnect.txt");
		List<InputStream> open = new ArrayList<>();
		write(file, "bye".getBytes("UTF-8"));
		for (int i = 0; i < 3; i++) {
			open.add(file.getInputStream());
		}
		for (InputStream in : open) {
			in.close();
		}
		List<SftpChannel> idle = pool.idleChannels();
		assertEquals(3, idle.size());

		SftpFileSourceFactory copy = (SftpFileSourceFactory) f.createThreadSafeCopy();
		assertTrue(copy.connect());
		assertTrue(copy.channelPool() == pool, "the copy shares the connection and its pool");
		f.disConnect();
		for (SftpChannel c : idle) {
			assertTrue(c.isOpen(), "another factory still holds the connection");
		}
		copy.disConnect();
		assertTrue(pool.idleChannels().isEmpty());
		for (SftpChannel c : idle) {
			assertFalse(c.isOpen(), "closed with the connection");
		}
		assertThrows(IOException.class, pool::borrow);
	}
}
