package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceRandomAccessStream;
import us.bringardner.io.filesource.IRandomAccessStream;
import us.bringardner.io.filesource.sftp.client.SftpChannel;

/**
 * Batch 20: random access that can write goes through MINA even when the
 * factory uses JSch, because JSch can only write at "the file's size + n"
 * and a size change by anyone else in between put the data in the wrong
 * place. Read-only random access stays on JSch.
 * <p>
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer).
 */
public class SftpBatch20Test {

	static final String DIR = "SftpBatch20Test";
	static final AtomicInteger KEYS = new AtomicInteger();

	static SftpFileSourceFactory jsch;
	static SftpFileSourceFactory mina;

	@BeforeAll
	static void setUp() throws IOException {
		jsch = TestServer.connect("jsch");
		mina = TestServer.connect("mina");
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

	static SftpFileSourceFactory factory(String impl) {
		return impl.equals("jsch") ? jsch : mina;
	}

	/** A connected factory with a connection of its own; 'key' null makes one up. */
	static SftpFileSourceFactory own(String impl, String key) throws IOException {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		Properties p = TestServer.properties(impl, TestServer.port());
		p.setProperty(SftpFileSourceFactory.PROP_SESSION_KEY,
				key != null ? key : "batch20-"+impl+"-"+KEYS.incrementAndGet()+"-"+System.nanoTime());
		f.setConnectionProperties(p);
		assertTrue(f.connect());
		return f;
	}

	/** Writes a file of 'size' zeros and returns its absolute path. */
	static String zeros(String impl, String name, int size) throws IOException {
		return SftpRegressionTest.write(jsch.createFileSource(DIR+"/"+impl+"-"+name), new byte[size]).getAbsolutePath();
	}

	/**
	 * The bug: while another program keeps changing the file's size, JSch
	 * random-access writes landed size-change bytes away from where they were
	 * asked to go (or failed). Every write here is read back from the server.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void writesLandWhereAskedWhileTheSizeChanges(String impl) throws Exception {
		String path = zeros(impl, "race.bin", 4096);
		AtomicBoolean stop = new AtomicBoolean();
		AtomicReference<Throwable> otherFailed = new AtomicReference<>();
		// the other program: grows the file to 8 KB and cuts it back to 4 KB, over and over
		Thread other = new Thread(() -> {
			try (SftpChannel c = mina.openSftp()) {
				while( !stop.get()) {
					c.truncate(path, 8192);
					c.truncate(path, 4096);
				}
			} catch (Throwable e) {
				otherFailed.set(e);
			}
		});
		other.setDaemon(true);
		other.start();

		FileSource f = factory(impl).createFileSource(path);
		SftpRandomAccessIoController io = new SftpRandomAccessIoController(f, "rw");
		try (IRandomAccessStream r = new FileSourceRandomAccessStream(io, "rw")) {
			for (int i = 0; i < 200; i++) {
				byte[] pattern = new byte[16];
				Arrays.fill(pattern, (byte) (i % 200 + 1));
				r.seek(100);
				r.write(pattern);
				io.save();   // to the server now
				byte[] back;
				try (InputStream in = mina.createFileSource(path).getInputStream(100)) {
					back = in.readNBytes(16);
				}
				assertArrayEquals(pattern, back, "write "+i+" isn't at position 100");
			}
		} finally {
			stop.set(true);
			other.join(10_000);
		}
		assertNull(otherFailed.get());
	}

	/** With JSch, a writable random-access file is on a MINA connection to the same account. */
	@Test
	void jschWritesGoThroughMina() throws Exception {
		SftpFileSourceFactory f = own("jsch", null);
		try {
			assertNull(f.randomAccessCompanion(), "nothing until it's needed");
			String path = zeros("jsch", "companion.bin", 10);
			try (IRandomAccessStream r = f.createFileSource(path).getRandomAccessStream("rw")) {
				r.seek(3);
				r.write(7);
			}
			SftpFileSourceFactory m = f.randomAccessCompanion();
			assertNotNull(m);
			assertEquals("mina", m.getEffectiveImplementation());
			assertTrue(m.isConnected());
			assertTrue(m.channelPool().openedCount() > 0, "its channel was used");
			assertEquals(7, readAt(path, 3), "the byte is where it was written");

			f.disConnect();
			assertFalse(m.isConnected(), "disconnected with the factory");
			assertNull(f.randomAccessCompanion());
		} finally {
			f.disConnect();
		}
	}

	static int readAt(String path, long pos) throws IOException {
		try (InputStream in = mina.createFileSource(path).getInputStream(pos)) {
			return in.read();
		}
	}

	/** Read-only random access needs no MINA connection: JSch reads at any position safely. */
	@Test
	void jschReadOnlyStaysOnJsch() throws Exception {
		SftpFileSourceFactory f = own("jsch", null);
		try {
			String path = zeros("jsch", "read-only.bin", 10);
			try (IRandomAccessStream r = f.createFileSource(path).getRandomAccessStream("r")) {
				assertEquals(0, r.read());
			}
			assertNull(f.randomAccessCompanion());
		} finally {
			f.disConnect();
		}
	}

	/** A MINA factory uses itself. */
	@Test
	void minaUsesItself() throws IOException {
		assertSame(mina, mina.randomAccessFactory());
		assertNull(mina.randomAccessCompanion());
	}

	/** An explicit session key gets its own MINA key, so the JSch connection is never shared with MINA. */
	@Test
	void explicitKeyGetsItsOwnMinaKey() throws Exception {
		String key = "batch20-explicit-"+System.nanoTime();
		SftpFileSourceFactory f = own("jsch", key);
		try {
			SftpFileSourceFactory m = f.randomAccessFactory();
			assertNotEquals(f.getSessionKey(), m.getSessionKey());
			assertEquals(key+"#mina", m.getSessionKey());
			assertNotEquals(f.getConnection(), m.getConnection());
		} finally {
			f.disConnect();
		}
	}
}
