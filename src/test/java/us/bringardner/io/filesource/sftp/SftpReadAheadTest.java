package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.IRandomAccessStream;
import us.bringardner.io.filesource.ISeekableInputStream;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpFile;

/**
 * Read-ahead for random access (batch 12): a sequential pass sends its read
 * requests ahead instead of waiting one round trip per chunk, and the data
 * read ahead is never returned once it's out of date. Each test runs with
 * both SSH libraries.
 * <p>
 * The speed tests connect through {@link DelayProxy}, which adds latency on
 * the way to the local SSH server; on plain localhost the gain doesn't show.
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer).
 */
public class SftpReadAheadTest {

	static final String DIR = "SftpReadAheadTest";
	/** Added each way, so a round trip takes at least twice this. */
	static final int DELAY_MS = 25;
	static final int ROUND_TRIP_MS = 2 * DELAY_MS;
	static final int CHUNK = 32 * 1024;
	/** 64 chunks. */
	static final int BIG = 64 * CHUNK;

	static SftpFileSourceFactory jsch;
	static SftpFileSourceFactory mina;
	static DelayProxy proxy;
	static SftpFileSourceFactory slowJsch;
	static SftpFileSourceFactory slowMina;

	static SftpFileSourceFactory connect(String implementation, int port) throws IOException {
		return TestServer.connect(implementation, port);
	}

	@BeforeAll
	static void setUp() throws IOException {
		jsch = TestServer.connect("jsch");
		mina = TestServer.connect("mina");
		proxy = new DelayProxy(TestServer.port(), DELAY_MS);
		slowJsch = connect("jsch", proxy.port());
		slowMina = connect("mina", proxy.port());
		// these tests count chunks of CHUNK bytes, not the default size
		for (SftpFileSourceFactory f : new SftpFileSourceFactory[] {jsch, mina, slowJsch, slowMina}) {
			f.setChunkSize(CHUNK);
		}
		FileSource dir = jsch.createFileSource(DIR);
		if( !dir.exists()) {
			assertTrue(dir.mkdirs(), "Can't create "+dir);
		}
	}

	@AfterAll
	static void tearDown() throws IOException {
		for (SftpFileSourceFactory f : new SftpFileSourceFactory[] {slowJsch, slowMina, mina}) {
			if( f != null ) {
				f.disConnect();
			}
		}
		if( proxy != null ) {
			proxy.close();
		}
		if( jsch != null ) {
			SftpRandomAccessTest.deleteAll(jsch.createFileSource(DIR));
			jsch.disConnect();
		}
	}

	static SftpFileSourceFactory factory(String implementation) {
		return implementation.equals("jsch") ? jsch : mina;
	}

	static SftpFileSourceFactory slowFactory(String implementation) {
		return implementation.equals("jsch") ? slowJsch : slowMina;
	}

	static byte[] random(int size, long seed) {
		byte[] b = new byte[size];
		new Random(seed).nextBytes(b);
		return b;
	}

	/** Writes a fresh file and returns its absolute path. */
	static String write(String implementation, String name, byte[] data) throws IOException {
		FileSource f = factory(implementation).createFileSource(DIR).getChild(implementation+"-"+name);
		try (OutputStream out = f.getOutputStream()) {
			out.write(data);
		}
		return f.getAbsolutePath();
	}

	/** Reads 'len' bytes at 'position', as many requests as it takes; fewer only at the end. */
	static byte[] readAt(SftpFile file, long position, int len) throws IOException {
		byte[] ret = new byte[len];
		int got = 0;
		while( got < len ) {
			int n = file.read(position + got, ret, got, len - got);
			if( n < 0 ) {
				break;
			}
			got += n;
		}
		return got == len ? ret : Arrays.copyOf(ret, got);
	}

	/** Reads forward from 'position' a chunk at a time, as the random-access code does. */
	static void readForward(SftpFile file, byte[] expected, long position, int chunks) throws IOException {
		for (int i = 0; i < chunks; i++) {
			long p = position + (long) i * CHUNK;
			assertArrayEquals(Arrays.copyOfRange(expected, (int) p, (int) p + CHUNK), readAt(file, p, CHUNK),
					"chunk at "+p);
		}
	}

	// ------------------------------------------------------------ correctness

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void seeksForwardAndBackwardReadTheRightBytes(String impl) throws IOException {
		byte[] data = random(BIG, 1);
		String path = write(impl, "seek.bin", data);
		try (SftpChannel ch = factory(impl).getConnection().openSftp();
				SftpFile file = ch.open(path, false)) {
			readForward(file, data, 0, 6);   // reads move forward: read-ahead is running
			assertArrayEquals(Arrays.copyOfRange(data, 1000, 1000 + 5000), readAt(file, 1000, 5000), "backward");
			assertArrayEquals(Arrays.copyOfRange(data, 1_500_000, 1_500_000 + 70_000),
					readAt(file, 1_500_000, 70_000), "forward");
			readForward(file, data, 10 * CHUNK, 8);
			assertArrayEquals(Arrays.copyOfRange(data, 3 * CHUNK + 7, 3 * CHUNK + 7 + 100),
					readAt(file, 3 * CHUNK + 7, 100), "backward into data read ahead before");
			readForward(file, data, 0, 64);   // a whole pass, through every read-ahead size
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void readAfterWriteSeesNewData(String impl) throws IOException {
		byte[] data = random(BIG, 2);
		String path = write(impl, "write.bin", data);
		try (SftpChannel ch = factory(impl).getConnection().openSftp();
				SftpFile file = ch.open(path, true)) {
			readForward(file, data, 0, 3);
			// ahead of the reads so far, inside what was read ahead
			byte[] patch = random(CHUNK, 3);
			file.write(4 * CHUNK + 10, patch, 0, patch.length);
			System.arraycopy(patch, 0, data, 4 * CHUNK + 10, patch.length);
			readForward(file, data, 3 * CHUNK, 10);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void readAfterTruncateSeesNewLength(String impl) throws IOException {
		byte[] data = random(BIG, 4);
		String path = write(impl, "truncate.bin", data);
		try (SftpChannel ch = factory(impl).getConnection().openSftp();
				SftpFile file = ch.open(path, true)) {
			readForward(file, data, 0, 3);
			file.truncate(4 * CHUNK + 100);
			byte[] rest = readAt(file, 3 * CHUNK, BIG);
			assertEquals(CHUNK + 100, rest.length, "stops at the new end");
			assertArrayEquals(Arrays.copyOfRange(data, 3 * CHUNK, 4 * CHUNK + 100), rest);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void readingToTheEndReturnsMinusOne(String impl) throws IOException {
		byte[] data = random(5 * CHUNK + 123, 5);
		String path = write(impl, "eof.bin", data);
		try (SftpChannel ch = factory(impl).getConnection().openSftp();
				SftpFile file = ch.open(path, false)) {
			byte[] buf = new byte[CHUNK];
			long pos = 0;
			int n;
			while( (n = file.read(pos, buf, 0, buf.length)) > 0 ) {
				assertArrayEquals(Arrays.copyOfRange(data, (int) pos, (int) pos + n), Arrays.copyOf(buf, n));
				pos += n;
			}
			assertEquals(-1, n);
			assertEquals(data.length, pos, "every byte before -1");
			assertEquals(-1, file.read(pos, buf, 0, buf.length), "still -1");
			assertEquals(-1, file.read(pos + 1000, buf, 0, buf.length), "past the end");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void readAtTheEndSeesTheFileGrow(String impl) throws IOException {
		byte[] data = random(3 * CHUNK, 6);
		String path = write(impl, "grow.bin", data);
		try (SftpChannel ch = factory(impl).getConnection().openSftp();
				SftpFile file = ch.open(path, false)) {
			readForward(file, data, 0, 3);
			byte[] buf = new byte[CHUNK];
			assertEquals(-1, file.read(data.length, buf, 0, buf.length));
			try (SftpChannel other = factory(impl).getConnection().openSftp();
					OutputStream out = other.write(path, true)) {
				out.write(new byte[] {1, 2, 3});
			}
			assertEquals(3, file.read(data.length, buf, 0, buf.length), "the new bytes, not a remembered -1");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void fifteenFilesInARowLeakNoChannels(String impl) throws IOException {
		// OpenSSH allows 10 channels per connection by default
		byte[] data = random(10 * CHUNK, 7);
		FileSource f = factory(impl).createFileSource(write(impl, "fifteen.bin", data));
		byte[] buf = new byte[3 * CHUNK];
		for (int i = 0; i < 15; i++) {
			try (IRandomAccessStream r = f.getRandomAccessStream("r")) {
				r.seek(CHUNK);
				r.readFully(buf);   // closes with read-ahead still in flight
				assertArrayEquals(Arrays.copyOfRange(data, CHUNK, 4 * CHUNK), buf, "file "+i);
			}
			ISeekableInputStream s = f.getSeekableInputStream();
			try {
				int got = 0;
				int n;
				while( got < buf.length && (n = s.read(buf, got, buf.length - got)) > 0 ) {
					got += n;
				}
				assertArrayEquals(Arrays.copyOf(data, buf.length), buf, "seekable "+i);
			} finally {
				s.close();
			}
		}
	}

	// ------------------------------------------------------------ speed

	/**
	 * Without read-ahead, 64 chunks take at least 64 round trips. With it,
	 * well under 20 (the read-ahead starts small and doubles).
	 */
	static void assertFewRoundTrips(String what, long elapsedMs) {
		System.out.println(what+": "+elapsedMs+" ms, "+(elapsedMs / ROUND_TRIP_MS)+" round trips");
		assertTrue(elapsedMs < 20 * ROUND_TRIP_MS,
				what+" took "+elapsedMs+" ms, "+(elapsedMs / ROUND_TRIP_MS)+" round trips of "+ROUND_TRIP_MS
				+" ms, for "+(BIG / CHUNK)+" chunks");
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void sequentialRandomAccessReadIsPipelined(String impl) throws Exception {
		byte[] data = random(BIG, 8);
		String path = write(impl, "speed-ras.bin", data);
		SftpFileSourceFactory slow = slowFactory(impl);
		assertEquals(CHUNK, slow.getChunkSize());
		FileSource f = slow.createFileSource(path);
		byte[] back = new byte[BIG];
		try (IRandomAccessStream r = f.getRandomAccessStream("r")) {
			r.length();   // the size isn't part of the pass
			long start = System.currentTimeMillis();
			r.readFully(back);
			assertFewRoundTrips(impl+" random access", System.currentTimeMillis() - start);
			assertEquals(-1, r.read());
		}
		assertArrayEquals(data, back);
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void sequentialSeekableReadIsPipelined(String impl) throws IOException {
		byte[] data = random(BIG, 9);
		String path = write(impl, "speed-seekable.bin", data);
		FileSource f = slowFactory(impl).createFileSource(path);
		byte[] back = new byte[BIG];
		ISeekableInputStream s = f.getSeekableInputStream();
		try {
			long start = System.currentTimeMillis();
			InputStream in = s.getInputStream();
			int got = 0;
			byte[] buf = new byte[8 * 1024];   // smaller than a chunk: reads go through the buffer
			int n;
			while( (n = in.read(buf)) > 0 ) {
				System.arraycopy(buf, 0, back, got, n);
				got += n;
			}
			assertFewRoundTrips(impl+" seekable", System.currentTimeMillis() - start);
			assertEquals(BIG, got);
		} finally {
			s.close();
		}
		assertArrayEquals(data, back);
	}

	/**
	 * A plain getInputStream() pass (batch 16). MINA's stream used to wait
	 * for each 32 KB request before sending the next: 64+ round trips here.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void sequentialStreamReadIsPipelined(String impl) throws IOException {
		byte[] data = random(BIG, 10);
		String path = write(impl, "speed-stream.bin", data);
		FileSource f = slowFactory(impl).createFileSource(path);
		byte[] back;
		try (InputStream in = f.getInputStream()) {
			long start = System.currentTimeMillis();   // opening the channel and file isn't part of the pass
			back = in.readAllBytes();
			assertFewRoundTrips(impl+" stream", System.currentTimeMillis() - start);
		}
		assertArrayEquals(data, back);
	}

	/** Streams from an offset, past the end, of an empty file, and read byte by byte. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void streamReadsFromAnyOffset(String impl) throws IOException {
		byte[] data = random(3 * CHUNK + 100, 11);
		FileSource f = factory(impl).createFileSource(write(impl, "offsets.bin", data));
		for (long offset : new long[] {0, 1, CHUNK - 1, CHUNK, 2 * CHUNK + 7, data.length - 1}) {
			try (InputStream in = f.getInputStream(offset)) {
				assertArrayEquals(Arrays.copyOfRange(data, (int) offset, data.length), in.readAllBytes(),
						"from "+offset);
				assertEquals(-1, in.read());
			}
		}
		try (InputStream in = f.getInputStream(data.length + 10)) {
			assertEquals(-1, in.read(), "past the end");
		}
		try (InputStream in = f.getInputStream(5)) {
			for (int i = 5; i < 5 + 2 * CHUNK; i++) {
				assertEquals(data[i] & 0xFF, in.read(), "byte "+i);
			}
		}
		FileSource empty = factory(impl).createFileSource(write(impl, "empty.bin", new byte[0]));
		try (InputStream in = empty.getInputStream()) {
			assertEquals(-1, in.read());
		}
	}

	/** Closing a stream early leaves the channel usable for the next one. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void streamClosedEarlyThenAnother(String impl) throws IOException {
		byte[] data = random(BIG, 12);
		FileSource f = factory(impl).createFileSource(write(impl, "early.bin", data));
		for (int i = 0; i < 5; i++) {
			try (InputStream in = f.getInputStream()) {
				byte[] b = new byte[100];
				assertEquals(100, in.readNBytes(b, 0, 100));
				assertArrayEquals(Arrays.copyOf(data, 100), b);
			}
		}
		try (InputStream in = f.getInputStream()) {
			assertArrayEquals(data, in.readAllBytes());
		}
	}

	/**
	 * A TCP proxy to localhost that holds each piece of data for a fixed time
	 * before passing it on, in order, both ways: latency without a bandwidth
	 * limit.
	 */
	static class DelayProxy implements AutoCloseable {
		private final ServerSocket server;
		private final int targetPort;
		private final long delayMs;
		/** While true, data is held back both ways, like a connection whose peer has vanished. */
		private volatile boolean frozen;

		DelayProxy(int targetPort, long delayMs) throws IOException {
			this.targetPort = targetPort;
			this.delayMs = delayMs;
			server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			daemon(this::accept);
		}

		int port() {
			return server.getLocalPort();
		}

		/** From now on nothing gets through, in either direction; nothing is closed either. */
		void freeze() {
			frozen = true;
		}

		private void accept() {
			try {
				while( true ) {
					Socket client = server.accept();
					Socket target = new Socket(InetAddress.getLoopbackAddress(), targetPort);
					client.setTcpNoDelay(true);
					target.setTcpNoDelay(true);
					pump(client, target);
					pump(target, client);
				}
			} catch (IOException e) {
				// closed
			}
		}

		private static final byte[] END = new byte[0];

		private static class Piece {
			final long due;
			final byte[] data;

			Piece(long due, byte[] data) {
				this.due = due;
				this.data = data;
			}
		}

		private void pump(Socket from, Socket to) {
			BlockingQueue<Piece> queue = new LinkedBlockingQueue<>();
			daemon(() -> {
				byte[] buf = new byte[64 * 1024];
				try (InputStream in = from.getInputStream()) {
					int n;
					while( (n = in.read(buf)) > 0 ) {
						queue.add(new Piece(System.nanoTime() + delayMs * 1_000_000, Arrays.copyOf(buf, n)));
					}
				} catch (IOException e) {
					// closed
				}
				queue.add(new Piece(System.nanoTime() + delayMs * 1_000_000, END));
			});
			daemon(() -> {
				try (OutputStream out = to.getOutputStream()) {
					while( true ) {
						Piece p = queue.take();
						long wait = p.due - System.nanoTime();
						if( wait > 0 ) {
							Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
						}
						while( frozen ) {
							Thread.sleep(10);
						}
						if( p.data == END ) {
							break;
						}
						out.write(p.data);
						out.flush();
					}
				} catch (IOException | InterruptedException e) {
					// closed
				}
				try {
					from.close();
					to.close();
				} catch (IOException e) {
					// ignore
				}
			});
		}

		private static void daemon(Runnable r) {
			Thread t = new Thread(r, "DelayProxy");
			t.setDaemon(true);
			t.start();
		}

		@Override
		public void close() throws IOException {
			server.close();
		}
	}
}
