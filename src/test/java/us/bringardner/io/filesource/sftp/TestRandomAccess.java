package us.bringardner.io.filesource.sftp;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.sshtools.common.files.AbstractFileFactory;
import com.sshtools.common.files.direct.NioFileFactory.NioFileFactoryBuilder;
import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.common.policy.FileFactory;
import com.sshtools.common.ssh.SshConnection;
import com.sshtools.server.InMemoryPasswordAuthenticator;
import com.sshtools.server.SshServer;
import com.sshtools.server.vsession.ShellCommandFactory;
import com.sshtools.server.vsession.VirtualChannelFactory;
import com.sshtools.server.vsession.commands.admin.AdminCommandFactory;
import com.sshtools.server.vsession.commands.fs.FileSystemCommandFactory;

import us.bringardner.io.filesource.FileSource;


@TestMethodOrder(OrderAnnotation.class)
public class TestRandomAccess {
	//https://jadaptive.com/java-ssh-library/maverick-synergy/creating-an-interactive-terminal/
	static SshServer server;
	static int port = 2222;
	static String user = "foo";
	static String password = "bar";
	static String host = "localhost";
	static int timeout = 5000;
	private static String remoteTestFileDirPath;
	private static SftpFileSourceFactory factory;
	public static String testDataString = "0123456789";
	public static byte[] testData = testDataString.getBytes();


	private FileSource testFile;

	@BeforeAll
	public static void setupBeforeAll() throws IOException {
		remoteTestFileDirPath = "target/SftpTest";

		server = new SshServer(port);

		server.addAuthenticator(new InMemoryPasswordAuthenticator().addUser(user,password.toCharArray()));
		server.setChannelFactory(new VirtualChannelFactory(
				new ShellCommandFactory(
					new AdminCommandFactory(),
						new FileSystemCommandFactory())));
		server.setFileFactory(new FileFactory() {
			
			@Override
			public AbstractFileFactory<?> getFileFactory(SshConnection con) throws IOException, PermissionDeniedException {
				return NioFileFactoryBuilder.create()
						.withCurrentDirectoryAsHome()
						.withoutSandbox()
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
		p.setProperty("host", host);
		p.setProperty("port", ""+port);
		p.setProperty("password",""+password);
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



	@Test
	@Order(1)
	public void createFile() throws IOException {
		FileSource remoteDir = factory.createFileSource(remoteTestFileDirPath);
		if( !remoteDir.exists()) {
			assertTrue(
					remoteDir.mkdirs(),
					"Cannot create remote directory"+remoteDir
					);			
		}

		testFile = remoteDir.getChild("RamUnitTests.txt");
		int targetLen = 10240;

		try(OutputStream out = testFile.getOutputStream()) {
			int size = 0;
			while( size < targetLen) {
				out.write(testData);
				size += testData.length;
			}

		}

	}

	@Test
	@Order(2)
	public void testReadRandomcIo() throws Exception {

		FileSource remoteDir = factory.createFileSource(remoteTestFileDirPath);

		testFile = remoteDir.getChild("RamUnitTests.txt");
		long len = testFile.length();
		long pos = 0;

		// Read forward
		try(SftpRandomAccessIoController ram = new SftpRandomAccessIoController((SftpFileSource) testFile)){
			long l2 = ram.length();
			assertEquals(len, l2,"Start lengths do not match");
			while(pos < l2) {
				int idx = (int) (pos % testData.length);
				int expect = testData[idx];
				int actual = ram.read(pos);
				assertEquals(expect, actual,"Read is wromg at pos="+pos);
				pos++;
			}
		}
		// Read backwards

		pos = testFile.length();
		try(SftpRandomAccessIoController ram = new SftpRandomAccessIoController((SftpFileSource) testFile)){
			long l2 = ram.length();
			assertEquals(len, l2,"Start lengths do not match");
			pos = l2-1;

			while(pos >= 0 ) {
				int idx = (int) (pos % testData.length);
				int expect = testData[idx];
				int actual = ram.read(pos);
				assertEquals(expect, actual,"Read is wromg at pos="+pos);
				pos--;
			}
		}

	}

	@Test
	@Order(3)
	public void testWriteRandomcIo() throws Exception {

		
		String alpa = "abcdefghijklmnopqrstuvwxyz";

		Map<Long,Integer> changes = new HashMap<>();

		FileSource remoteDir = factory.createFileSource(remoteTestFileDirPath);

		testFile = remoteDir.getChild("RamUnitTests.txt");
		
		long targetLen = testFile.length();
		try(OutputStream out = testFile.getOutputStream()) {
			int size = 0;
			int chunk = 0;
			while( size < targetLen) {
				String tmp = String.format("|--%4d--|", chunk++);
				out.write(tmp.getBytes());
				size += tmp.length();
			}

		}

		
		
		
		long len = testFile.length();
		int targetChangeCount = 100;

		//  randomly make changes
		try(SftpRandomAccessIoController ram = new SftpRandomAccessIoController((SftpFileSource) testFile)){
			Random r = new Random();

			for(int idx=0; idx < targetChangeCount; idx++ ) {
				long pos = r.nextInt((int)len);
				while( changes.containsKey(pos)) {
					pos = r.nextInt((int)len);
				}
				
				@SuppressWarnings("unused")
				int current = ram.read(pos);
				int value = alpa.charAt(r.nextInt(alpa.length()));
				ram.write(pos,(byte) value);
				int updated = ram.read(pos);
				assertEquals(value, updated,"Updated char is wrong");
				changes.put(pos, value);
			}

		}

		//  validate changes
		try(SftpRandomAccessIoController ram = new SftpRandomAccessIoController((SftpFileSource) testFile)){
			int idx = 0;
			for(Long pos : changes.keySet()) {
				int expect = changes.get(pos);
				int actual = ram.read(pos);
				char e = (char)expect;
				char a = (char)actual;

				if( actual != expect) {					
					System.out.println("Wrong e="+e+" a="+a);
				}
				assertEquals(expect, actual,"Validate: Updated char is wrong at idx="+idx+" pos="+pos);
				idx++;
			}
		}
	}

}
