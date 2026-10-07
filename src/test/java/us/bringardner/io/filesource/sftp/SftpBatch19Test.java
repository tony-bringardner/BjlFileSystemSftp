package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.sftp.client.SftpChannel;

/**
 * Batch 19: calls (stat, list, mkdir...) borrow channels from the shared
 * pool instead of queueing for the factory's own channel, and the pool
 * keeps to a channel limit (maxChannels), with room kept for calls and for
 * commands. Each test runs with both SSH libraries.
 * <p>
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer). Tests of the server's own channel limit need
 * OpenSSH.
 */
public class SftpBatch19Test {

	static final String DIR = "SftpBatch19Test";
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

	/** Writes 'count' small files in 'dir'; returns their paths. */
	static List<String> files(String dir, int count) throws IOException {
		List<String> ret = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			FileSource f = SftpRegressionTest.write(jsch.createFileSource(dir+"/f"+i+".txt"), ("file "+i).getBytes("UTF-8"));
			ret.add(f.getAbsolutePath());
		}
		return ret;
	}

	/**
	 * A connected factory with a connection of its own (a unique session key)
	 * and the given channel settings; null leaves the default.
	 */
	static SftpFileSourceFactory own(String impl, int port, Integer maxChannels, Long waitMs) throws IOException {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		Properties p = TestServer.properties(impl, port);
		p.setProperty(SftpFileSourceFactory.PROP_SESSION_KEY, "batch19-"+impl+"-"+KEYS.incrementAndGet()+"-"+System.nanoTime());
		if( maxChannels != null ) {
			p.setProperty(SftpFileSourceFactory.PROP_MAX_CHANNELS, ""+maxChannels);
		}
		if( waitMs != null ) {
			p.setProperty(SftpFileSourceFactory.PROP_CHANNEL_WAIT_TIMEOUT, ""+waitMs);
		}
		f.setConnectionProperties(p);
		assertTrue(f.connect());
		return f;
	}

	/** Runs 'task' on 'threads' threads at once and waits for them all; rethrows the first failure. */
	static void together(int threads, IoTask task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<?>> results = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				int id = t;
				results.add(pool.submit(() -> {
					go.await();
					task.run(id);
					return null;
				}));
			}
			go.countDown();
			for (Future<?> r : results) {
				r.get(120, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	interface IoTask {
		void run(int thread) throws Exception;
	}

	// ------------------------------------------------------------ #13 calls on pooled channels

	/**
	 * Calls from different threads used to take turns on the factory's one
	 * channel. Through a 50 ms round trip, 4 threads x 5 stats took 20 round
	 * trips; now about 5.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void callsFromDifferentThreadsRunTogether(String impl) throws Exception {
		List<String> paths = files(freshDir(impl, "parallel"), 4);
		try (SftpReadAheadTest.DelayProxy proxy = new SftpReadAheadTest.DelayProxy(TestServer.port(), SftpReadAheadTest.DELAY_MS)) {
			SftpFileSourceFactory f = TestServer.connect(impl, proxy.port());
			try {
				together(4, t -> f.stat(paths.get(t)));   // opens the channels; not timed
				long start = System.currentTimeMillis();
				together(4, t -> {
					for (int i = 0; i < 5; i++) {
						assertEquals(6, f.stat(paths.get(t)).getSize());
					}
				});
				long ms = System.currentTimeMillis() - start;
				System.out.println(impl+" 4 threads x 5 stats: "+ms+" ms, "+(ms / SftpReadAheadTest.ROUND_TRIP_MS)+" round trips");
				assertTrue(ms < 12 * SftpReadAheadTest.ROUND_TRIP_MS,
						"took "+ms+" ms: "+(ms / SftpReadAheadTest.ROUND_TRIP_MS)+" round trips of "+SftpReadAheadTest.ROUND_TRIP_MS+" ms");
			} finally {
				f.disConnect();
			}
		}
	}

	/**
	 * "No such file" is the server's answer, not a broken channel: checking
	 * for missing files must not close channels and open new ones.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void missingFileChecksKeepTheirChannel(String impl) throws IOException {
		SftpFileSourceFactory f = factory(impl);
		String d = freshDir(impl, "missing");
		f.stat(d);   // a channel in the pool
		SftpChannelPool pool = f.channelPool();
		int opened = pool.openedCount();
		for (int i = 0; i < 50; i++) {
			assertFalse(f.createFileSource(d+"/nothing"+i).exists());
		}
		assertEquals(opened, pool.openedCount(), "channels opened");
	}

	/**
	 * Every factory, and every createThreadSafeCopy(), used to keep a channel
	 * open all the time. Twelve of them on one connection were more than
	 * OpenSSH allows (10), so the later ones couldn't connect.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void idleFactoriesHoldNoChannels(String impl) throws Exception {
		TestServer.assumeOpenSsh();
		List<String> paths = files(freshDir(impl, "copies"), 1);
		SftpFileSourceFactory base = own(impl, TestServer.port(), null, null);
		List<SftpFileSourceFactory> copies = new ArrayList<>();
		try {
			for (int i = 0; i < 12; i++) {
				SftpFileSourceFactory c = (SftpFileSourceFactory) base.createThreadSafeCopy();
				copies.add(c);
				assertTrue(c.connect(), "copy "+i);
				assertEquals(6, c.stat(paths.get(0)).getSize());
			}
			try (InputStream in = base.createFileSource(paths.get(0)).getInputStream()) {
				assertEquals(6, in.readAllBytes().length);
			}
			for (SftpFileSourceFactory c : copies) {
				assertEquals(6, c.stat(paths.get(0)).getSize());
			}
			assertTrue(base.channelPool().idleChannels().size() <= SftpChannelPool.MAX_IDLE);
		} finally {
			for (SftpFileSourceFactory c : copies) {
				c.disConnect();
			}
			base.disConnect();
		}
	}

	// ------------------------------------------------------------ #5 the channel limit

	/** However many threads, no more than maxChannels channels are ever open. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void limitIsNeverExceeded(String impl) throws Exception {
		List<String> paths = files(freshDir(impl, "limit"), 4);
		SftpFileSourceFactory f = own(impl, TestServer.port(), 4, 30_000L);
		SftpChannelPool pool = f.channelPool();
		AtomicInteger most = new AtomicInteger();
		AtomicBoolean done = new AtomicBoolean();
		Thread watch = new Thread(() -> {
			while( !done.get()) {
				most.accumulateAndGet(pool.openCount(), Math::max);
				Thread.onSpinWait();
			}
		});
		watch.setDaemon(true);
		watch.start();
		try {
			together(12, t -> {
				String path = paths.get(t % paths.size());
				try (InputStream in = f.createFileSource(path).getInputStream()) {
					assertEquals('f', in.read());
					Thread.sleep(100);   // hold it a while
					assertEquals(6, f.stat(path).getSize());
				}
			});
		} finally {
			done.set(true);
			watch.join(5000);
			f.disConnect();
		}
		assertTrue(most.get() <= 4, "at most 4 open, saw "+most.get());
	}

	/**
	 * Streams can't take the last 2 channels, so a call still gets one while
	 * every thread with a stream is busy; a stream past its share waits, then
	 * fails with a message that says why.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void streamsLeaveRoomForCalls(String impl) throws Exception {
		List<String> paths = files(freshDir(impl, "room"), 1);
		String path = paths.get(0);
		SftpFileSourceFactory f = own(impl, TestServer.port(), 4, 500L);
		List<InputStream> held = new ArrayList<>();
		try {
			held.add(f.createFileSource(path).getInputStream());
			held.add(f.createFileSource(path).getInputStream());   // 2 = 4 - the 2 kept for calls

			long start = System.currentTimeMillis();
			IOException e = assertThrows(IOException.class, () -> f.createFileSource(path).getInputStream());
			assertTrue(System.currentTimeMillis() - start >= 450, "it waited first");
			assertTrue(e.getMessage().contains("in use"), e.getMessage());

			start = System.currentTimeMillis();
			assertEquals(6, f.stat(path).getSize());
			assertTrue(f.createFileSource(path).exists());
			assertTrue(System.currentTimeMillis() - start < 400, "a call doesn't wait");
		} finally {
			for (InputStream in : held) {
				in.close();
			}
			f.disConnect();
		}
	}

	/** A stream waiting for a channel gets the first one given back. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void aWaitingStreamGetsTheNextChannelBack(String impl) throws Exception {
		List<String> paths = files(freshDir(impl, "wait"), 1);
		String path = paths.get(0);
		SftpFileSourceFactory f = own(impl, TestServer.port(), 3, 10_000L);   // 1 for streams
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			InputStream first = f.createFileSource(path).getInputStream();
			Future<Integer> second = pool.submit(() -> {
				try (InputStream in = f.createFileSource(path).getInputStream()) {
					return in.read();
				}
			});
			Thread.sleep(300);
			assertFalse(second.isDone(), "it waits while the only stream channel is in use");
			long start = System.currentTimeMillis();
			first.close();
			assertEquals('f', second.get(5, TimeUnit.SECONDS));
			assertTrue(System.currentTimeMillis() - start < 2000);
		} finally {
			pool.shutdownNow();
			f.disConnect();
		}
	}

	/**
	 * OpenSSH counts command channels against its 10 too. With all 10 taken by
	 * SFTP channels, a command waits for one to come back instead of being
	 * refused by the server.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void aCommandWaitsForASlot(String impl) throws Exception {
		TestServer.assumeOpenSsh();
		List<String> paths = files(freshDir(impl, "command"), 1);
		String path = paths.get(0);
		SftpFileSourceFactory f = own(impl, TestServer.port(), 10, 10_000L);
		List<InputStream> streams = new ArrayList<>();
		List<SftpChannel> calls = new ArrayList<>();
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			for (int i = 0; i < 8; i++) {
				streams.add(f.createFileSource(path).getInputStream());
			}
			calls.add(f.channelPool().borrow(SftpChannelPool.Use.CALL));
			calls.add(f.channelPool().borrow(SftpChannelPool.Use.CALL));
			assertEquals(10, f.channelPool().openCount());

			Future<String> command = pool.submit(() -> f.runCommand("echo slot"));
			Thread.sleep(300);
			assertFalse(command.isDone(), "it waits for a slot");
			calls.remove(0).close();
			assertEquals("slot\n", command.get(10, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
			for (SftpChannel c : calls) {
				c.close();
			}
			for (InputStream in : streams) {
				in.close();
			}
			f.disConnect();
		}
	}

	// ------------------------------------------------------------ the properties

	@Test
	void channelPropertiesAreSettable() throws IOException {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		assertEquals(8, f.getMaxChannels());
		assertEquals(30_000, f.getChannelWaitTimeout());
		Properties p = f.getConnectProperties();
		assertEquals("8", p.getProperty(SftpFileSourceFactory.PROP_MAX_CHANNELS));
		assertEquals("30000", p.getProperty(SftpFileSourceFactory.PROP_CHANNEL_WAIT_TIMEOUT));

		p.setProperty(SftpFileSourceFactory.PROP_MAX_CHANNELS, "5");
		p.setProperty(SftpFileSourceFactory.PROP_CHANNEL_WAIT_TIMEOUT, "1234");
		f.setConnectionProperties(p);
		assertEquals(5, f.getMaxChannels());
		assertEquals(1234, f.getChannelWaitTimeout());

		SftpFileSourceFactory copy = (SftpFileSourceFactory) f.createThreadSafeCopy();
		assertEquals(5, copy.getMaxChannels());
		assertEquals(1234, copy.getChannelWaitTimeout());

		Properties partial = new Properties();
		partial.setProperty(SftpFileSourceFactory.PROP_HOST, "example.com");
		f.setConnectionProperties(partial);
		assertEquals(5, f.getMaxChannels(), "a property that isn't given is left alone");

		assertThrows(IllegalArgumentException.class, () -> f.setMaxChannels(0));
	}

	/** getConnectProperties() wrote the session key, but setConnectionProperties ignored it. */
	@Test
	void sessionKeyPropertyIsRead() {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		Properties p = f.getConnectProperties();
		p.setProperty(SftpFileSourceFactory.PROP_SESSION_KEY, "my-key");
		f.setConnectionProperties(p);
		assertEquals("my-key", f.getSessionKey());
		assertEquals("my-key", f.getConnectProperties().getProperty(SftpFileSourceFactory.PROP_SESSION_KEY));
		p.setProperty(SftpFileSourceFactory.PROP_SESSION_KEY, "");
		f.setConnectionProperties(p);
		assertFalse(f.getSessionKey().equals("my-key"), "empty clears it");
	}

	/** The connection's pool uses the factory's settings. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina", "bjl"})
	void thePoolUsesTheSettings(String impl) throws IOException {
		SftpFileSourceFactory f = own(impl, TestServer.port(), 5, null);
		try {
			assertEquals(5, f.channelPool().maxChannels());
		} finally {
			f.disConnect();
		}
	}
}
