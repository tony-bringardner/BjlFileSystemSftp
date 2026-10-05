package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.stream.Stream;

import com.sshtools.common.files.direct.NioFileFactory.NioFileFactoryBuilder;
import com.sshtools.common.publickey.SshKeyPairGenerator;
import com.sshtools.server.InMemoryPasswordAuthenticator;
import com.sshtools.server.SshServer;

/**
 * The SSH server the tests connect to, chosen once per test run by the
 * system property bjl.sftp.test.server:
 * <ul>
 * <li>"auto" (the default): the OpenSSH server on localhost:22 if unittest1 /
 * 0000 can log in there, otherwise the embedded server.</li>
 * <li>"local": the server on localhost:22, with the accounts unittest1 to
 * unittest4 (see CLAUDE.md). Tests fail if it isn't there.</li>
 * <li>"embedded": an embedded Maverick server, started on a free port, with
 * one account (unittest1 / 0000) whose home directory is
 * target/embedded-sftp/home. It needs nothing installed, so CI can run the
 * tests.</li>
 * </ul>
 * Tests that need real OpenSSH (links, Unix permissions and groups, other
 * accounts, shell commands, OpenSSH's channel limit) call
 * {@link #assumeOpenSsh()}, which skips them, with a message, on the embedded
 * server.
 */
final class TestServer {

	static final String SYSTEM_PROPERTY = "bjl.sftp.test.server";
	static final String HOST = "localhost";
	static final String USER = "unittest1";
	static final String PASSWORD = "0000";
	static final int LOCAL_PORT = 22;

	private static SshServer embedded;
	private static int embeddedPort;
	/** The resolved choice; null until first asked. */
	private static Boolean local;

	private TestServer() {
	}

	/** True when the tests use the OpenSSH server on localhost:22. */
	static synchronized boolean isLocal() {
		if( local == null ) {
			String mode = System.getProperty(SYSTEM_PROPERTY, "auto").trim().toLowerCase();
			switch (mode) {
			case "local":
				local = true;
				break;
			case "embedded":
				local = false;
				break;
			case "auto":
				local = canLogInLocally();
				break;
			default:
				throw new IllegalArgumentException("-D"+SYSTEM_PROPERTY+" must be auto, local or embedded, not "+mode);
			}
			System.out.println("SFTP tests use "+(local
					? "the OpenSSH server on "+HOST+":"+LOCAL_PORT
					: "the embedded SSH server; tests that need OpenSSH are skipped"));
		}
		return local;
	}

	/** True if unittest1 / 0000 can log in on localhost:22. */
	private static boolean canLogInLocally() {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), LOCAL_PORT), 2000);
		} catch (IOException e) {
			return false;   // nothing listening: don't wait for an SSH timeout
		}
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		Properties p = properties(null, LOCAL_PORT);
		p.setProperty(SftpFileSourceFactory.PROP_CONNECT_TIMEOUT, "5000");
		f.setConnectionProperties(p);
		try {
			return f.connect();
		} catch (IOException | RuntimeException e) {
			return false;
		} finally {
			try {
				f.disConnect();
			} catch (IOException e) {
				// only a probe
			}
		}
	}

	/** The port of the server the tests use, starting the embedded one if needed. */
	static synchronized int port() throws IOException {
		if( isLocal()) {
			return LOCAL_PORT;
		}
		if( embedded == null ) {
			startEmbedded();
		}
		return embeddedPort;
	}

	/** A factory connected as unittest1 to the server the tests use. */
	static SftpFileSourceFactory connect(String implementation) throws IOException {
		return connect(implementation, port());
	}

	static SftpFileSourceFactory connect(String implementation, int port) throws IOException {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		f.setConnectionProperties(properties(implementation, port));
		assertTrue(f.connect(), "Factory did not connect to "+HOST+":"+port);
		return f;
	}

	/** Connection properties for unittest1 on the given port; a null implementation means the default. */
	static Properties properties(String implementation, int port) {
		Properties p = new SftpFileSourceFactory().getConnectProperties();
		p.setProperty(SftpFileSourceFactory.PROP_USER, USER);
		p.setProperty(SftpFileSourceFactory.PROP_HOST, HOST);
		p.setProperty(SftpFileSourceFactory.PROP_PORT, ""+port);
		p.setProperty(SftpFileSourceFactory.PROP_PASSWORD, PASSWORD);
		if( implementation != null ) {
			p.setProperty(SftpFileSourceFactory.PROP_IMPLEMENTATION, implementation);
		}
		return p;
	}

	/**
	 * Skips the calling test (or the whole class, from @BeforeAll) unless the
	 * tests use the OpenSSH server on localhost:22.
	 */
	static void assumeOpenSsh() {
		assumeTrue(isLocal(), "Needs the OpenSSH server on "+HOST+":"+LOCAL_PORT+" with the unittest accounts");
	}

	private static void startEmbedded() throws IOException {
		Path root = new File("target/embedded-sftp").getAbsoluteFile().toPath();
		deleteAll(root);
		File home = root.resolve("home").toFile();
		if( !home.mkdirs()) {
			throw new IOException("Can't create "+home);
		}
		try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			embeddedPort = s.getLocalPort();
		}
		try {
			SshServer server = new SshServer(InetAddress.getLoopbackAddress(), embeddedPort);
			server.addHostKey(SshKeyPairGenerator.generateKeyPair(SshKeyPairGenerator.ED25519));
			server.addAuthenticator(new InMemoryPasswordAuthenticator().addUser(USER, PASSWORD.toCharArray()));
			server.setFileFactory(con -> NioFileFactoryBuilder.create()
					.withHome(home)
					.withSandbox(true)   // "/" is the home directory; nothing outside it is reachable
					.build());
			server.start();
			embedded = server;
		} catch (Exception e) {
			throw new IOException("Can't start the embedded SSH server: "+e, e);
		}
		Runtime.getRuntime().addShutdownHook(new Thread(embedded::close, "embedded SSH server shutdown"));
	}

	private static void deleteAll(Path dir) throws IOException {
		if( Files.exists(dir)) {
			try (Stream<Path> paths = Files.walk(dir)) {
				for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
					Files.delete(p);
				}
			}
		}
	}
}
