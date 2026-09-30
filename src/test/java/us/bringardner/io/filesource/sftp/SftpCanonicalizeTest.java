package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import us.bringardner.io.filesource.sftp.client.SftpAttributes;

/**
 * BJL-12: the canonical path rules, against a made-up server tree, so relative link
 * targets and error cases are covered without an SSH server.
 */
public class SftpCanonicalizeTest {

	private static final String DIR = "dir", FILE = "file";

	/** path -> DIR, FILE, or a link target; anything else doesn't exist. */
	static class FakeServer extends SftpFileSourceFactory {
		private static final long serialVersionUID = 1L;
		final Map<String, String> tree = new HashMap<>();
		int lstats;

		FakeServer put(String path, String what) {
			tree.put(path, what);
			return this;
		}

		@Override
		public SftpAttributes lstat(String path) throws IOException {
			lstats++;
			String what = tree.get(path);
			if( what == null ) {
				throw new NoSuchFileException(path);
			}
			int mode = what.equals(DIR) ? 0040755 : what.equals(FILE) ? 0100644 : 0120777;
			return new SftpAttributes(0, 0, 0, mode, 0, 0);
		}

		@Override
		public String readlink(String path) throws IOException {
			String what = tree.get(path);
			if( what == null || what.equals(DIR) || what.equals(FILE)) {
				throw new IOException("not a link: "+path);
			}
			return what;
		}
	}

	private static FakeServer server() {
		return new FakeServer()
				.put("/srv", DIR).put("/srv/root", DIR).put("/srv/root/sub", DIR)
				.put("/srv/root/sub/file.txt", FILE)
				.put("/srv/outside", DIR).put("/srv/outside/secret.txt", FILE)
				.put("/srv/root/up", "../outside")          // relative, escapes
				.put("/srv/root/in", "sub")                 // relative, stays inside
				.put("/srv/root/abs", "/srv/outside")       // absolute
				.put("/srv/root/chain", "in/../up")         // link to a path through links
				.put("/srv/root/sub/back", "../..")         // relative with ".." only
				.put("/srv/root/a", "b").put("/srv/root/b", "a");  // loop
	}

	private static String canon(FakeServer f, String path) throws IOException {
		return SftpFileSource.canonicalize(f, path);
	}

	@Test
	public void dotsAndSlashes() throws IOException {
		FakeServer f = server();
		assertEquals("/", canon(f, "/"));
		assertEquals("/", canon(f, "/.."));
		assertEquals("/srv/root/sub/file.txt", canon(f, "//srv/./root/sub/../sub//file.txt/"));
	}

	@Test
	public void relativeTargetsAreRelativeToTheLinksDirectory() throws IOException {
		FakeServer f = server();
		assertEquals("/srv/outside/secret.txt", canon(f, "/srv/root/up/secret.txt"));
		assertEquals("/srv/root/sub/file.txt", canon(f, "/srv/root/in/file.txt"));
		assertEquals("/srv/outside", canon(f, "/srv/root/abs"));
		assertEquals("/srv/outside", canon(f, "/srv/root/chain"));
		assertEquals("/srv", canon(f, "/srv/root/sub/back"));
		// ".." after a link goes to the target's parent
		assertEquals("/srv", canon(f, "/srv/root/up/.."));
	}

	@Test
	public void missingPartsAreResolvedByName() throws IOException {
		FakeServer f = server();
		assertEquals("/srv/root/new/deeper", canon(f, "/srv/root/new/deeper"));
		assertEquals("/srv/root/also-new", canon(f, "/srv/root/new/../also-new"));
		// ".." back into existing directories: links are followed again
		assertEquals("/srv/outside", canon(f, "/srv/root/new/../up"));
		assertEquals("/srv/outside", canon(f, "/srv/root/new/x/../../up"));
	}

	@Test
	public void belowAMissingElementTheServerIsntAsked() throws IOException {
		FakeServer f = server();
		canon(f, "/srv/root/new/a/b/c/d");
		// /srv, /srv/root, /srv/root/new
		assertEquals(3, f.lstats);
	}

	@Test
	public void loopsFail() {
		FakeServer f = server();
		assertThrows(IOException.class, () -> canon(f, "/srv/root/a"));
	}

	@Test
	public void otherServerErrorsFail() {
		FakeServer f = new FakeServer() {
			private static final long serialVersionUID = 1L;
			@Override
			public SftpAttributes lstat(String path) throws IOException {
				throw new IOException("Permission denied");
			}
		};
		assertThrows(IOException.class, () -> canon(f, "/srv/root"));
	}
}
