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
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.IRandomAccessStream;
import us.bringardner.io.filesource.ISeekableInputStream;
import us.bringardner.io.filesource.java.file.FileSourcePath;

/**
 * FileSource.getRandomAccessStream and getSeekableInputStream over SFTP,
 * directly and through java.nio (Files.newByteChannel). Each test runs with
 * both SSH libraries.
 *
 * Uses the same local SSH server and account as the other SFTP tests
 * (localhost:22, unittest1 / 0000).
 */
public class SftpRandomAccessTest {

	static final String DIR = "SftpRandomAccessTest";
	static SftpFileSourceFactory jsch;
	static SftpFileSourceFactory mina;

	static SftpFileSourceFactory connect(String implementation) throws IOException {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		Properties p = f.getConnectProperties();
		p.setProperty("user", "unittest1");
		p.setProperty("host", "localhost");
		p.setProperty("port", "22");
		p.setProperty("password", "0000");
		p.setProperty("implementation", implementation);
		f.setConnectionProperties(p);
		assertTrue(f.connect(), "Factory did not connect");
		return f;
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
		if( jsch != null ) {
			deleteAll(jsch.createFileSource(DIR));
			jsch.disConnect();
		}
		if( mina != null ) {
			mina.disConnect();
		}
	}

	static void deleteAll(FileSource f) throws IOException {
		if( f.isDirectory()) {
			for (FileSource k : f.listFiles()) {
				deleteAll(k);
			}
		}
		f.delete();
	}

	static SftpFileSourceFactory factory(String implementation) {
		return implementation.equals("jsch") ? jsch : mina;
	}

	/** A fresh file for one test; any old copy is removed. */
	static FileSource file(String implementation, String name) throws IOException {
		FileSource f = factory(implementation).createFileSource(DIR).getChild(implementation+"-"+name);
		if( f.exists()) {
			f.delete();
		}
		return f;
	}

	static FileSource write(String implementation, String name, byte[] data) throws IOException {
		FileSource f = file(implementation, name);
		try (OutputStream out = f.getOutputStream()) {
			out.write(data);
		}
		return f;
	}

	/** Every byte value, several times, so reads cross chunk boundaries. */
	static byte[] data(int size) {
		byte[] b = new byte[size];
		for (int i = 0; i < size; i++) {
			b[i] = (byte) i;
		}
		return b;
	}

	static byte[] readAll(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return in.readAllBytes();
		}
	}

	// ------------------------------------------------------------ random access

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void rwCreatesWritesSeeksAndReads(String impl) throws IOException {
		FileSource f = file(impl, "rw.bin");
		assertFalse(f.exists());
		try (IRandomAccessStream r = f.getRandomAccessStream("rw")) {
			assertTrue(f.exists(), "\"rw\" creates the file on open");
			assertEquals(0, r.length());
			r.write(data(1000));
			r.seek(100_000);
			r.write(new byte[] {(byte) 0xFF, (byte) 0x80});
			assertEquals(100_002, r.length());

			r.seek(250);
			assertEquals(250, r.read(), "unsigned byte");
			assertEquals(251, r.read());
			r.seek(100_000);
			assertEquals(0xFF, r.read(), "bytes >= 0x80 are not EOF");
			assertEquals(0x80, r.read());
			assertEquals(-1, r.read(), "EOF");
		}
		byte[] disk = readAll(f);
		assertEquals(100_002, disk.length);
		assertArrayEquals(data(1000), Arrays.copyOf(disk, 1000));
		assertEquals(0, disk[50_000], "the gap reads as zeros");
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void rwOverwritesInPlace(String impl) throws IOException {
		FileSource f = write(impl, "patch.txt", "Hello, world!".getBytes("UTF-8"));
		try (IRandomAccessStream r = f.getRandomAccessStream("rw")) {
			r.seek(7);
			r.write("SFTP!".getBytes("UTF-8"));
		}
		assertEquals("Hello, SFTP!!", new String(readAll(f), "UTF-8"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void setLengthShrinksAndGrows(String impl) throws IOException {
		FileSource f = write(impl, "len.bin", data(5000));
		try (IRandomAccessStream r = f.getRandomAccessStream("rw")) {
			r.setLength(1234);
			assertEquals(1234, r.length());
			r.setLength(9000);
			assertEquals(9000, r.length());
		}
		f.refresh();
		assertEquals(9000, f.length());
		byte[] disk = readAll(f);
		assertArrayEquals(Arrays.copyOf(data(5000), 1234), Arrays.copyOf(disk, 1234));
		assertEquals(0, disk[5000], "beyond the old end reads as zeros");
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void readOnlyModeReadsButCannotWrite(String impl) throws IOException {
		FileSource f = write(impl, "ro.bin", data(300));
		try (IRandomAccessStream r = f.getRandomAccessStream("r")) {
			r.seek(200);
			assertEquals(200, r.read());
			assertThrows(IOException.class, () -> r.write(1));
			assertThrows(IOException.class, () -> r.setLength(10));
		}
		assertArrayEquals(data(300), readAll(f), "file unchanged");
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void openErrorsLikeRandomAccessFile(String impl) throws IOException {
		FileSource missing = file(impl, "missing.bin");
		assertThrows(FileNotFoundException.class, () -> missing.getRandomAccessStream("r"));
		assertFalse(missing.exists(), "\"r\" must not create the file");

		FileSource dir = factory(impl).createFileSource(DIR);
		assertThrows(FileNotFoundException.class, () -> dir.getRandomAccessStream("r"));
		assertThrows(FileNotFoundException.class, () -> dir.getRandomAccessStream("rw"));

		FileSource f = write(impl, "mode.bin", data(10));
		assertThrows(IllegalArgumentException.class, () -> f.getRandomAccessStream("x"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void randomBinaryMatchesAfterManyWrites(String impl) throws IOException {
		byte[] expected = new byte[200_000];
		new Random(42).nextBytes(expected);
		FileSource f = write(impl, "random.bin", expected);
		Random r = new Random(7);
		try (IRandomAccessStream ras = f.getRandomAccessStream("rw")) {
			for (int i = 0; i < 200; i++) {
				int pos = r.nextInt(expected.length - 100);
				byte[] patch = new byte[1 + r.nextInt(99)];
				r.nextBytes(patch);
				ras.seek(pos);
				ras.write(patch);
				System.arraycopy(patch, 0, expected, pos, patch.length);
			}
			ras.seek(0);
			byte[] back = new byte[expected.length];
			int got = 0;
			while( got < back.length ) {
				int n = ras.read(back, got, back.length - got);
				assertTrue(n > 0, "unexpected EOF at "+got);
				got += n;
			}
			assertArrayEquals(expected, back, "read back through the same stream");
		}
		assertArrayEquals(expected, readAll(f), "read back from the server");
	}

	// ------------------------------------------------------------ seekable input

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void seekableReadsUnsignedBytesAndSeeks(String impl) throws IOException {
		byte[] all = data(100_000);
		FileSource f = write(impl, "seek.bin", all);
		ISeekableInputStream s = f.getSeekableInputStream();
		try {
			assertEquals(all.length, s.length());
			for (int i = 0; i < 256; i++) {
				assertEquals(i, s.read(), "byte "+i);
			}
			s.seek(99_990);
			byte[] tail = new byte[20];
			int n = s.read(tail, 0, tail.length);
			assertEquals(10, n, "stops at the end");
			assertArrayEquals(Arrays.copyOfRange(all, 99_990, 100_000), Arrays.copyOf(tail, 10));
			assertEquals(-1, s.read(), "EOF");

			s.seek(5);   // backwards
			assertEquals(5, s.read());
			assertEquals(6, s.getFilePointer());

			s.seek(1_000_000);   // past the end is allowed
			assertEquals(-1, s.read());
			assertThrows(IOException.class, () -> s.seek(-1));

			s.seek(1000);
			byte[] big = new byte[64 * 1024];   // larger than the buffer
			int total = 0;
			while( total < big.length ) {
				int k = s.read(big, total, big.length - total);
				assertTrue(k > 0);
				total += k;
			}
			assertArrayEquals(Arrays.copyOfRange(all, 1000, 1000 + big.length), big);
		} finally {
			s.close();
		}
		assertThrows(IOException.class, s::read, "closed");
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void seekableInputStreamViewSharesThePointer(String impl) throws IOException {
		byte[] all = new byte[1000];
		new Random(7).nextBytes(all);
		FileSource f = write(impl, "view.bin", all);
		ISeekableInputStream s = f.getSeekableInputStream();
		s.seek(100);
		try (InputStream in = s.getInputStream()) {
			assertEquals(900, in.available());
			byte[] buf = new byte[50];
			assertEquals(50, in.read(buf));
			assertArrayEquals(Arrays.copyOfRange(all, 100, 150), buf);
			assertEquals(150, s.getFilePointer(), "position is shared");
			assertEquals(50, in.skip(50));
			assertEquals(all[200] & 0xFF, in.read());
			assertEquals(799, in.skip(10_000), "skip stops at EOF");
			assertEquals(0, in.available());
			assertEquals(-1, in.read(buf));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void seekableOpenErrors(String impl) throws IOException {
		FileSource missing = file(impl, "nothere.bin");
		assertThrows(FileNotFoundException.class, missing::getSeekableInputStream);
		assertThrows(FileNotFoundException.class, missing::getInputStream);
		assertThrows(FileNotFoundException.class, () -> missing.getInputStream(0));
		assertFalse(missing.exists(), "reading must not create the file");
		FileSource dir = factory(impl).createFileSource(DIR);
		assertThrows(FileNotFoundException.class, dir::getSeekableInputStream);
	}

	// ------------------------------------------------------------ java.nio

	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void nioByteChannelReadWrite(String impl) throws IOException {
		FileSource f = write(impl, "nio.txt", "0123456789".getBytes("UTF-8"));
		Path p = new FileSourcePath(f);
		try (SeekableByteChannel ch = Files.newByteChannel(p, EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE))) {
			assertEquals(10, ch.size());
			ch.position(3);
			ch.write(ByteBuffer.wrap("abc".getBytes("UTF-8")));
			ch.position(0);
			ByteBuffer b = ByteBuffer.allocate(10);
			while( b.hasRemaining() && ch.read(b) > 0 ) {
				// keep reading
			}
			assertEquals("012abc6789", new String(b.array(), "UTF-8"));
			ch.truncate(5);
			assertEquals(5, ch.size());
		}
		assertEquals("012ab", new String(Files.readAllBytes(p), "UTF-8"));

		// WRITE without TRUNCATE_EXISTING overwrites from the start and keeps the rest
		Files.write(p, "XY".getBytes("UTF-8"), StandardOpenOption.WRITE);
		assertEquals("XY2ab", new String(readAll(f), "UTF-8"));
	}
}
