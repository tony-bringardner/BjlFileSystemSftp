package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sshtools.common.files.AbstractFileFactory;
import com.sshtools.common.files.direct.NioFileFactory.NioFileFactoryBuilder;
import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.common.policy.FileFactory;
import com.sshtools.common.ssh.SshConnection;
import com.sshtools.server.InMemoryPasswordAuthenticator;
import com.sshtools.server.SshServer;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.fileproxy.FileProxyFactory;

/**
 * BJL-12: getCanonicalPath() meets the contract the shared isChildOfMine relies on
 * (absolute, "." and ".." resolved, symbolic links followed), and isChildOfMine
 * can't be escaped. Uses an in-process SSH server that serves the local file system,
 * so the links are made directly with java.nio.
 */
public class SftpCanonicalPathTest {

	static final int PORT = 2224;
	static final String USER = "canon";
	static final String PASSWORD = "path";

	static SshServer server;
	static SftpFileSourceFactory factory;
	static Path base;
	static boolean links;

	@BeforeAll
	public static void setUp() throws Exception {
		base = Paths.get("target", "SftpCanonicalPathTest").toAbsolutePath();
		deleteAll(base);
		Files.createDirectories(base.resolve("root/sub"));
		Path root = base.resolve("root");
		write(root.resolve("sub/file.txt"));
		write(Files.createDirectories(base.resolve("rootX")).resolve("x.txt"));
		write(Files.createDirectories(base.resolve("outside")).resolve("secret.txt"));
		try {
			// absolute targets: this test server's readlink mishandles relative ones
			// (SftpCanonicalizeTest covers relative targets without a server)
			Files.createSymbolicLink(root.resolve("link-out"), base.resolve("outside"));
			Files.createSymbolicLink(root.resolve("abs-link"), base.resolve("outside"));
			Files.createSymbolicLink(root.resolve("link-in"), root.resolve("sub"));
			Files.createSymbolicLink(root.resolve("loop1"), root.resolve("loop2"));
			Files.createSymbolicLink(root.resolve("loop2"), root.resolve("loop1"));
			links = true;
		} catch (IOException | UnsupportedOperationException e) {
			links = false;   // e.g. Windows without the privilege
		}

		server = new SshServer(PORT);
		server.addAuthenticator(new InMemoryPasswordAuthenticator().addUser(USER, PASSWORD.toCharArray()));
		server.setFileFactory(new FileFactory() {
			@Override
			public AbstractFileFactory<?> getFileFactory(SshConnection con) throws IOException, PermissionDeniedException {
				return NioFileFactoryBuilder.create().withCurrentDirectoryAsHome().withoutSandbox().build();
			}
		});
		server.start();
		long start = System.currentTimeMillis();
		while( !server.isRunning() && System.currentTimeMillis()-start < 5000) {
			Thread.sleep(100);
		}
		assertTrue(server.isRunning(), "SSH server did not start");

		factory = new SftpFileSourceFactory();
		Properties p = factory.getConnectProperties();
		p.setProperty(SftpFileSourceFactory.PROP_USER, USER);
		p.setProperty(SftpFileSourceFactory.PROP_HOST, "localhost");
		p.setProperty(SftpFileSourceFactory.PROP_PORT, ""+PORT);
		p.setProperty(SftpFileSourceFactory.PROP_PASSWORD, PASSWORD);
		// always ask the server: links are created behind its back
		p.setProperty(SftpFileSourceFactory.PROP_ATTRIBUTE_CACHE_TTL, "0");
		factory.setConnectionProperties(p);
		assertTrue(factory.connect(), "factory did not connect");
	}

	@AfterAll
	public static void tearDown() throws Exception {
		if( factory != null ) {
			factory.disConnect();
		}
		if( server != null ) {
			server.stop();
		}
		deleteAll(base);
	}

	private static FileSource remote(String relative) throws IOException {
		return factory.createFileSource(base.toString().replace('\\', '/')+"/"+relative);
	}

	private static String real(String relative) throws IOException {
		return base.resolve(relative).toRealPath().toString().replace('\\', '/');
	}

	@Test
	public void theServerSeesTheLocalFiles() throws IOException {
		assertTrue(remote("root/sub/file.txt").exists());
	}

	@Test
	public void dotsAreResolved() throws IOException {
		assertEquals(real("root"), remote("root").getCanonicalPath());
		assertEquals(real("root/sub/file.txt"), remote("root/./sub/../sub/file.txt").getCanonicalPath());
		// below a missing element, by name
		assertEquals(real("root")+"/also-new", remote("root/new/../also-new").getCanonicalPath());
		assertEquals(real("root")+"/new/file", remote("root/new/file").getCanonicalPath());
	}

	@Test
	public void linksAreFollowed() throws IOException {
		assumeTrue(links, "symbolic links not supported here");
		assertEquals(real("outside/secret.txt"), remote("root/link-out/secret.txt").getCanonicalPath());
		assertEquals(real("outside"), remote("root/abs-link").getCanonicalPath());
		assertEquals(real("root/sub/file.txt"), remote("root/link-in/file.txt").getCanonicalPath());
		// ".." after a link is the target's parent, as in POSIX
		assertEquals(real("rootX"), remote("root/link-out/../rootX").getCanonicalPath());
		// a missing element, then ".." back into real directories: links count again
		assertEquals(real("outside"), remote("root/missing/../link-out").getCanonicalPath());
		assertThrows(IOException.class, () -> remote("root/loop1").getCanonicalPath());
	}

	@Test
	public void isChildOfMineCantBeEscaped() throws IOException {
		FileSource root = remote("root");
		assertTrue(root.isChildOfMine(root));
		assertTrue(root.isChildOfMine(remote("root/sub")));
		assertTrue(root.isChildOfMine(remote("root/sub/file.txt")));
		assertTrue(root.isChildOfMine(remote("root/sub/../sub/file.txt")));
		assertTrue(root.isChildOfMine(remote("root/new/../also-new")));

		assertFalse(root.isChildOfMine(remote("root/..")));
		assertFalse(root.isChildOfMine(remote("root/../outside/secret.txt")));
		assertFalse(root.isChildOfMine(remote("root/sub/../../outside/secret.txt")));
		// same name prefix, different directory
		assertFalse(root.isChildOfMine(remote("rootX/x.txt")));
		assertFalse(root.isChildOfMine(remote("root/a/../../rootX/x.txt")));
		assertFalse(root.isChildOfMine(remote("")));
		assertFalse(root.isChildOfMine(null));

		if( links ) {
			assertTrue(root.isChildOfMine(remote("root/link-in/file.txt")));
			assertFalse(root.isChildOfMine(remote("root/link-out")));
			assertFalse(root.isChildOfMine(remote("root/link-out/secret.txt")));
			assertFalse(root.isChildOfMine(remote("root/abs-link/secret.txt")));
			assertFalse(root.isChildOfMine(remote("root/missing/../link-out/secret.txt")));
			// can't be resolved: not inside
			assertFalse(root.isChildOfMine(remote("root/loop1")));
		}
	}

	@Test
	public void theSharedIsChildOfMineIsUsed() {
		assertThrows(NoSuchMethodException.class,
				() -> SftpFileSource.class.getDeclaredMethod("isChildOfMine", FileSource.class));
	}

	@Test
	public void sameFileSystemMeansSameServerAccount() {
		assertTrue(factory.isSameFileSystem(factory));
		assertTrue(factory.isSameFileSystem(account("LOCALHOST", PORT, USER)));
		assertFalse(factory.isSameFileSystem(account("localhost", PORT, "someone-else")));
		assertFalse(factory.isSameFileSystem(account("localhost", PORT+1, USER)));
		assertFalse(factory.isSameFileSystem(account("otherhost", PORT, USER)));
		assertFalse(factory.isSameFileSystem(new FileProxyFactory()));
	}

	private static SftpFileSourceFactory account(String host, int port, String user) {
		SftpFileSourceFactory f = new SftpFileSourceFactory();
		Properties p = f.getConnectProperties();
		p.setProperty(SftpFileSourceFactory.PROP_HOST, host);
		p.setProperty(SftpFileSourceFactory.PROP_PORT, ""+port);
		p.setProperty(SftpFileSourceFactory.PROP_USER, user);
		f.setConnectionProperties(p);
		return f;
	}

	private static void write(Path file) throws IOException {
		Files.write(file, "data".getBytes(StandardCharsets.UTF_8));
	}

	private static void deleteAll(Path dir) throws IOException {
		if( !Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try(Stream<Path> s = Files.walk(dir)) {
			s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}
}
