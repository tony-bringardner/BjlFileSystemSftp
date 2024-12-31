package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import com.sshtools.common.files.AbstractFileFactory;
import com.sshtools.common.files.direct.NioFileFactory.NioFileFactoryBuilder;
import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.common.policy.FileFactory;
import com.sshtools.common.ssh.SshConnection;
import com.sshtools.server.InMemoryPasswordAuthenticator;
import com.sshtools.server.SshServer;



public class TestSftpWithLocalHost extends FileSourceAbstractTestClass {
	//https://jadaptive.com/java-ssh-library/maverick-synergy/creating-an-interactive-terminal/
	static SshServer server;
	static int port = 2222;
	static String user = "foo";
	static String password = "bar";
	static int timeout = 5000;

	@BeforeAll
	public static void setupBeforeAll() throws IOException {
		localTestFileDirPath="TestFiles";
		localCacheDirPath = "target/LocalCache";
		remoteTestFileDirPath = "target/SftpTest";

		server = new SshServer(port);
		server.addAuthenticator(new InMemoryPasswordAuthenticator().addUser(user,password.toCharArray()));
		server.setFileFactory(new FileFactory() {
			@Override
			public AbstractFileFactory<?> getFileFactory(SshConnection con) throws IOException, PermissionDeniedException {
				return NioFileFactoryBuilder.create()
						.withCurrentDirectoryAsHome()
						//.withoutSandbox()
						.build();
			}
		});

		server.start();

		long start = System.currentTimeMillis();

		while(!server.isRunning() && System.currentTimeMillis()-start < timeout) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
		}
		assertTrue(server.isRunning(),"SSH Server did not start.");


		factory = new SftpFileSourceFactory();

		Properties p = factory.getConnectProperties();

		p.setProperty("user", user);
		p.setProperty("host", "localhost");
		p.setProperty("port", ""+port);
		p.setProperty("password",password);
		factory.setConnectionProperties(p);		

		assertTrue(factory.connect(),"Factory did not start.");


	}

	@AfterAll
	public static void teardownAfterAll() throws IOException {
		factory.disConnect();
		server.stop();
		long start = System.currentTimeMillis();

		while(server.isRunning() && System.currentTimeMillis()-start < timeout) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
		}
		assertFalse(server.isRunning(),"SSH Server did not stop.");
		//  Cleanup after the ssh server
		File dir = new File(".").getCanonicalFile();
		for(File file : dir.listFiles()) {
			if( file.isFile()) {
				if( file.getName().startsWith("ssh_host_")) {
					file.delete();
				}
			}
		}
	}

}
