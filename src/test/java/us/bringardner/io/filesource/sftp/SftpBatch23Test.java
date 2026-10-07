package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.IRandomAccessStream;
import us.bringardner.io.filesource.sftp.client.SftpAttributes;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SshProviders;

/**
 * Batch 23: a third SSH library, BJL's own (bjl_net_ssh, "bjl"), selected like the others
 * with the implementation property. Everything else in the suite can run on it with
 * -Dbjl.sftp.implementation=bjl; the tests parameterized by library include it.
 * <p>
 * Connects to the test server: OpenSSH on localhost:22 or the embedded server (see TestServer).
 */
public class SftpBatch23Test {

	private static SftpFileSourceFactory bjl;

	@BeforeAll
	static void connect() throws IOException {
		bjl = TestServer.connect(SshProviders.BJL);
	}

	@AfterAll
	static void disconnect() throws IOException {
		if( bjl != null ) {
			bjl.disConnect();
		}
	}

	@Test
	void bjlIsAnImplementation() {
		assertEquals(SshProviders.BJL, SshProviders.get("bjl").getName());
		assertEquals(SshProviders.BJL, SshProviders.get("BJL").getName());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SshProviders.get("putty"));
		assertTrue(e.getMessage().contains("bjl"), e.getMessage());
		assertEquals(SshProviders.BJL, bjl.getEffectiveImplementation());
	}

	/**
	 * BJL writes at an offset in one request, like MINA, so random access that can write uses
	 * the factory's own connection; before, every implementation but MINA got a MINA companion.
	 */
	@Test
	void bjlUsesItselfForRandomAccessWrites() throws IOException {
		assertSame(bjl, bjl.randomAccessFactory());
		assertNull(bjl.randomAccessCompanion());
	}

	private static FileSource dir(String name) throws IOException {
		FileSource d = bjl.createFileSource(bjl.getCurrentDirectory().getAbsolutePath()+"/"+name+"-"+System.nanoTime());
		assertTrue(d.mkdirs());
		return d;
	}

	/** Streams, listing, rename, random access and delete through FileSource on the BJL client */
	@Test
	void fileSourceOnBjl() throws IOException {
		FileSource d = dir("batch23");
		try {
			byte[] data = new byte[3*1024*1024+17];
			new Random(23).nextBytes(data);
			FileSource f = d.getChild("data.bin");
			try (OutputStream out = f.getOutputStream()) {
				out.write(data);
			}
			f.refresh();
			assertEquals(data.length, f.length());
			try (InputStream in = f.getInputStream()) {
				assertArrayEquals(data, in.readAllBytes());
			}
			assertEquals(1, d.listFiles().length);

			// Random access writes at positions, on this connection
			try (IRandomAccessStream raf = f.getRandomAccessStream("rw")) {
				raf.seek(1000);
				raf.write("BJL".getBytes(StandardCharsets.UTF_8));
				raf.seek(data.length);
				raf.write("END".getBytes(StandardCharsets.UTF_8));
			}
			try (InputStream in = f.getInputStream()) {
				byte[] got = in.readAllBytes();
				assertEquals(data.length+3, got.length);
				assertEquals("BJL", new String(got, 1000, 3, StandardCharsets.UTF_8));
				assertEquals("END", new String(got, data.length, 3, StandardCharsets.UTF_8));
			}

			FileSource g = d.getChild("renamed.bin");
			assertTrue(f.renameTo(g));
			assertFalse(f.exists());
			assertTrue(g.delete());
		} finally {
			d.delete();
		}
	}

	/** The channel interface directly: attributes, errors as java.nio exceptions */
	@Test
	void channelOnBjl() throws IOException {
		FileSource d = dir("batch23ch");
		try (SftpChannel ch = bjl.getConnection().openSftp()) {
			String p = d.getAbsolutePath()+"/x.txt";
			try (OutputStream out = ch.write(p, false)) {
				out.write("hello".getBytes(StandardCharsets.UTF_8));
			}
			SftpAttributes a = ch.stat(p);
			assertEquals(5, a.getSize());
			assertTrue(a.isReg());
			ch.setModifiedTime(p, 1_300_000_000);
			assertEquals(1_300_000_000, ch.stat(p).getMTime());
			assertTrue(ch.createNew(d.getAbsolutePath()+"/new.txt"));
			assertFalse(ch.createNew(p), "never replaces what is there");
			assertEquals(5, ch.stat(p).getSize());
			assertThrows(java.nio.file.NoSuchFileException.class, () -> ch.stat(d.getAbsolutePath()+"/missing"));
			ch.remove(p);
			ch.remove(d.getAbsolutePath()+"/new.txt");
		} finally {
			d.delete();
		}
	}
}
