package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.sftp.client.SshConnection;

/**
 * Batch 18: connecting and closing don't hold the JVM-wide sessions lock,
 * so one slow server doesn't hold up factories for others, while factories
 * with the same key still share one connect. Each test runs with both SSH
 * libraries.
 * <p>
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer).
 */
public class SftpBatch18Test {

	static final String DIR = "SftpBatch18Test";
	/** A reserved documentation address (TEST-NET-1): nothing answers there. */
	static final String UNREACHABLE = "192.0.2.1";
	/** Gives each test its own connections, whatever the other tests have open. */
	static final AtomicInteger KEYS = new AtomicInteger();

	static SftpFileSourceFactory jsch;

	@BeforeAll
	static void setUp() throws IOException {
		jsch = TestServer.connect("jsch");
		FileSource dir = jsch.createFileSource(DIR);
		if( !dir.exists()) {
			assertTrue(dir.mkdirs(), "Can't create "+dir);
		}
	}

	@AfterAll
	static void tearDown() throws IOException {
		if( jsch != null ) {
			SftpRandomAccessTest.deleteAll(jsch.createFileSource(DIR));
			jsch.disConnect();
		}
	}

	/** A session key no other factory uses. */
	static String newKey(String impl) {
		return "batch18-"+impl+"-"+KEYS.incrementAndGet()+"-"+System.nanoTime();
	}

	/** An unconnected factory for unittest1 on 'port', with the given session key. */
	static SftpFileSourceFactory newFactory(String impl, int port, String key) {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		Properties p = TestServer.properties(impl, port);
		p.setProperty(SftpFileSourceFactory.PROP_SESSION_KEY, key);
		f.setConnectionProperties(p);
		return f;
	}

	static Thread daemon(Runnable r) {
		Thread t = new Thread(r, "SftpBatch18Test");
		t.setDaemon(true);
		t.start();
		return t;
	}

	/** A connect to a server that doesn't answer used to hold up every other connect in the JVM. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void slowConnectDoesNotHoldUpOthers(String impl) throws Exception {
		SftpFileSourceFactory slow = newFactory(impl, TestServer.port(), newKey(impl));
		slow.setHost(UNREACHABLE);
		slow.setConnectTimeout(5000);
		Thread t = daemon(() -> {
			try {
				slow.connect();
			} catch (IOException e) {
				// expected
			}
		});
		Thread.sleep(300);
		assumeTrue(t.isAlive(), UNREACHABLE+" was refused at once on this network, so there's nothing to wait for");

		SftpFileSourceFactory f = newFactory(impl, TestServer.port(), newKey(impl));
		try {
			long start = System.currentTimeMillis();
			assertTrue(f.connect());
			long ms = System.currentTimeMillis() - start;
			assertTrue(ms < 2500, "connecting took "+ms+" ms while another connect was waiting for "+UNREACHABLE);
			assertTrue(t.isAlive(), "the slow connect should still be going");
		} finally {
			f.disConnect();
			t.join(10_000);
		}
	}

	/** Factories with the same key that connect at the same time share one connection. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void sameKeySharesOneConnect(String impl) throws Exception {
		String key = newKey(impl);
		List<SftpFileSourceFactory> factories = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			factories.add(newFactory(impl, TestServer.port(), key));
		}
		ExecutorService pool = Executors.newFixedThreadPool(factories.size());
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Boolean>> results = new ArrayList<>();
			for (SftpFileSourceFactory f : factories) {
				results.add(pool.submit(() -> {
					go.await();
					return f.connect();
				}));
			}
			go.countDown();
			for (Future<Boolean> r : results) {
				assertTrue(r.get(30, TimeUnit.SECONDS));
			}
			SshConnection first = factories.get(0).getConnection();
			for (SftpFileSourceFactory f : factories) {
				assertSame(first, f.getConnection(), "one connection for one key");
			}
		} finally {
			pool.shutdownNow();
			for (SftpFileSourceFactory f : factories) {
				f.disConnect();
			}
		}
	}

	/**
	 * A failed connect fails everyone waiting on it, and isn't remembered:
	 * the next connect with the key tries again.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void failedConnectIsSharedThenForgotten(String impl) throws Exception {
		String key = newKey(impl);
		List<SftpFileSourceFactory> factories = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			SftpFileSourceFactory f = newFactory(impl, TestServer.port(), key);
			f.setPassword("wrong-password");
			factories.add(f);
		}
		ExecutorService pool = Executors.newFixedThreadPool(factories.size());
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Boolean>> results = new ArrayList<>();
			for (SftpFileSourceFactory f : factories) {
				results.add(pool.submit(() -> {
					go.await();
					return f.connect();
				}));
			}
			go.countDown();
			for (Future<Boolean> r : results) {
				java.util.concurrent.ExecutionException e = assertThrows(java.util.concurrent.ExecutionException.class,
						() -> r.get(60, TimeUnit.SECONDS));
				assertTrue(e.getCause() instanceof IOException, "failed with "+e.getCause());
			}
		} finally {
			pool.shutdownNow();
		}
		SftpFileSourceFactory good = newFactory(impl, TestServer.port(), key);
		try {
			assertTrue(good.connect(), "the failure isn't remembered");
		} finally {
			good.disConnect();
		}
	}

	/**
	 * Closing a connection whose server has gone silent takes a while (MINA
	 * waits up to 10 s for each channel's close to be confirmed). That used to
	 * happen inside the sessions lock, holding up every connect in the JVM.
	 * (Before batch 19 the factory's own channel was closed first, outside
	 * the lock, so this test took 10 s longer with MINA.)
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void slowCloseDoesNotHoldUpOthers(String impl) throws Exception {
		try (SftpReadAheadTest.DelayProxy proxy = new SftpReadAheadTest.DelayProxy(TestServer.port(), 0)) {
			SftpFileSourceFactory closing = newFactory(impl, proxy.port(), newKey(impl));
			assertTrue(closing.connect());
			// a stream leaves an idle channel in the pool, which disConnect() closes
			FileSource x = SftpRegressionTest.write(closing.createFileSource(DIR+"/"+impl+"-close.txt"), new byte[] {1, 2});
			try (InputStream in = x.getInputStream()) {
				assertArrayEquals(new byte[] {1, 2}, in.readAllBytes());
			}
			proxy.freeze();
			daemon(() -> {
				try {
					closing.disConnect();
				} catch (IOException e) {
					// closing anyway
				}
			});

			// wait until it has let go of the connection and is closing it
			long deadline = System.currentTimeMillis() + 20_000;
			while( closing.isConnected() && System.currentTimeMillis() < deadline ) {
				Thread.sleep(20);
			}
			Thread.sleep(300);

			SftpFileSourceFactory f = newFactory(impl, TestServer.port(), newKey(impl));
			try {
				long start = System.currentTimeMillis();
				assertTrue(f.connect());
				long ms = System.currentTimeMillis() - start;
				assertTrue(ms < 2500, "connecting took "+ms+" ms while another connection was closing");
			} finally {
				f.disConnect();
			}
			// 't' is left to finish closing in the background; nothing waits for it
		}
	}
}
