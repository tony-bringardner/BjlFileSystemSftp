package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.IRandomAccessStream;

/**
 * Batch 22: strictHostKeyChecking defaults to "yes", so a factory connects
 * only to servers whose host key is in known_hosts; a rejected key gives an
 * error that says what to do. Each connecting test runs with both SSH
 * libraries.
 * <p>
 * Connects to the test server: OpenSSH on localhost:22 or the embedded
 * server (see TestServer). The test servers' keys aren't in any known_hosts
 * file, so these tests make their own.
 */
public class SftpBatch22Test {

	/** Another server's key, in known_hosts format: never the test server's. */
	static final String SOMEONE_ELSES_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";

	/** A factory for the test server that leaves host key checking at its default. */
	static SftpFileSourceFactory checking(String impl, Path knownHosts) throws IOException {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		Properties p = TestServer.properties(impl, TestServer.port());
		p.remove(SftpFileSourceFactory.PROP_STRICT_HOST_KEY_CHECKING);   // TestServer turns it off
		p.setProperty(SftpFileSourceFactory.PROP_KNOWN_HOSTS, knownHosts.toString());
		p.setProperty(SftpFileSourceFactory.PROP_SESSION_KEY, "batch22-"+impl+"-"+System.nanoTime());
		f.setConnectionProperties(p);
		return f;
	}

	/** The host as known_hosts writes it: "host" on port 22, else "[host]:port". */
	static String hostEntry(int port) {
		return port == 22 ? TestServer.HOST : "["+TestServer.HOST+"]:"+port;
	}

	@Test
	void checkingIsOnByDefault() {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		assertEquals("yes", f.getStrictHostKeyChecking());
		assertEquals("yes", f.getConnectProperties().getProperty(SftpFileSourceFactory.PROP_STRICT_HOST_KEY_CHECKING));
		f.setStrictHostKeyChecking("no");
		assertEquals("no", f.getStrictHostKeyChecking());
		f.setStrictHostKeyChecking("");
		assertEquals("yes", f.getStrictHostKeyChecking(), "empty means the default");
		f.setStrictHostKeyChecking(null);
		assertEquals("yes", f.getStrictHostKeyChecking());
	}

	/** With nothing set, a server whose key isn't known is refused (it used to be accepted). */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void unknownServerIsRefusedByDefault(String impl) throws IOException {
		Path empty = Files.createTempFile("known_hosts", "");
		try {
			SftpFileSourceFactory f = checking(impl, empty);
			IOException e = assertThrows(IOException.class, f::connect);
			String m = e.getMessage();
			assertTrue(m.contains(empty.toString()), "names the file: "+m);
			assertTrue(m.contains("ssh-keyscan -p "+TestServer.port()+" "+TestServer.HOST), "says how to add the key: "+m);
			assertTrue(m.contains("strictHostKeyChecking=no"), "and how to turn checking off: "+m);
			assertTrue(!f.isConnected());
		} finally {
			Files.deleteIfExists(empty);
		}
	}

	/** A known_hosts entry with a different key for this server is refused too. */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void changedKeyIsRefused(String impl) throws IOException {
		Path wrong = Files.createTempFile("known_hosts", "");
		try {
			Files.writeString(wrong, hostEntry(TestServer.port())+" "+SOMEONE_ELSES_KEY+"\n");
			SftpFileSourceFactory f = checking(impl, wrong);
			IOException e = assertThrows(IOException.class, f::connect);
			assertTrue(e.getMessage().contains("doesn't match"), e.getMessage());
		} finally {
			Files.deleteIfExists(wrong);
		}
	}

	/**
	 * With the server's key in known_hosts (from ssh-keyscan, as the error
	 * suggests), the default connects, and random-access writes, which go
	 * through MINA even on a JSch factory, check the key the same way.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"jsch", "mina"})
	void knownServerConnectsByDefault(String impl) throws Exception {
		Path known = Files.createTempFile("known_hosts", "");
		try {
			String scanned = keyscan(TestServer.port());
			if( scanned == null || scanned.isBlank()) {
				// ssh-keyscan finds nothing on the embedded server; ask it directly
				scanned = hostEntry(TestServer.port())+" "+serverKey(TestServer.port())+"\n";
			}
			Files.writeString(known, scanned);
			SftpFileSourceFactory f = checking(impl, known);
			try {
				assertTrue(f.connect());
				FileSource x = f.createFileSource("SftpBatch22Test-"+impl+".bin");
				try (IRandomAccessStream r = x.getRandomAccessStream("rw")) {
					r.write(new byte[] {1, 2, 3});
				}
				assertEquals(3, x.length());
				x.delete();
			} finally {
				f.disConnect();
			}
		} finally {
			Files.deleteIfExists(known);
		}
	}

	/**
	 * The server's host key, "type base64", read during a key exchange that
	 * accepts any key. (Test code may use MINA directly.)
	 */
	static String serverKey(int port) throws Exception {
		java.util.concurrent.atomic.AtomicReference<java.security.PublicKey> key = new java.util.concurrent.atomic.AtomicReference<>();
		org.apache.sshd.client.SshClient client = org.apache.sshd.client.SshClient.setUpDefaultClient();
		client.setServerKeyVerifier((session, address, serverKey) -> {
			key.set(serverKey);
			return true;
		});
		client.start();
		try (org.apache.sshd.client.session.ClientSession session = client.connect(TestServer.USER, TestServer.HOST, port)
				.verify(java.time.Duration.ofSeconds(30)).getSession()) {
			session.addPasswordIdentity(TestServer.PASSWORD);
			session.auth().verify(java.time.Duration.ofSeconds(30));
		} finally {
			client.stop();
		}
		return org.apache.sshd.common.config.keys.PublicKeyEntry.toString(key.get());
	}

	/** The server's keys as known_hosts lines, or null if ssh-keyscan can't be run. */
	static String keyscan(int port) throws InterruptedException {
		try {
			Process p = new ProcessBuilder("ssh-keyscan", "-p", ""+port, TestServer.HOST)
					.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			byte[] out = p.getInputStream().readAllBytes();
			if( !p.waitFor(30, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return null;
			}
			return new String(out, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
	}
}
