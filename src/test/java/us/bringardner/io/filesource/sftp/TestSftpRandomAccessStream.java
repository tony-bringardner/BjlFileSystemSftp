package us.bringardner.io.filesource.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
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

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceRandomAccessStream;




@TestMethodOrder(OrderAnnotation.class)
public class TestSftpRandomAccessStream {


	enum Action{Write,Read,Seek,SetLength}
	class Entry {
		Action action;
		long value;
		long pointerBefore;
		long pointerAfter;
		long jpointerBefore;
		long jpointerAfter;


		public Entry(Action action) {
			this.action = action;
		}
	}
	static String remoteTestFileDirPath = "target/SftpTest";
	static SftpFileSourceFactory factory;
	static int chunk_size = 100;
	static long targetFileSize=500;
	static String testDataString = "0123456789";
	static byte [] testData = testDataString.getBytes();
	static SftpFileSource file;
	static SftpFileSource testDir;
	static SshServer server;
	static int port = 2222;
	static String user = "foo";
	static String password = "bar";
	static String host = "localhost";
	static int timeout = 5000;


	@BeforeAll
	public static void setup() throws IOException {


		server = new SshServer(port);

		server.addAuthenticator(new InMemoryPasswordAuthenticator().addUser(user,password.toCharArray()));
		/*
		server.setChannelFactory(new VirtualChannelFactory(
				new ShellCommandFactory(
					new AdminCommandFactory(),
						new FileSystemCommandFactory())));
		 */
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
		/*
		port = 22;
		user = "tony";
		password = "0000";
		host = "bringardner.us";
		 */

		p.setProperty("user", user);
		p.setProperty("host", host);
		p.setProperty("port", ""+port);
		p.setProperty("password",""+password);
		factory.setConnectionProperties(p);		

		assertTrue(factory.connect(),"Factory did not start.");

		FileSource remoteDir = factory.createFileSource(remoteTestFileDirPath);
		if( !remoteDir.exists()) {
			assertTrue(
					remoteDir.mkdirs(),
					"Cannot create remote directory"+remoteDir
					);			
		}


		testDir = (SftpFileSource) remoteDir.getChild("UnitTestDir");
		if( !testDir.exists()) {
			assertTrue(testDir.mkdirs(),"Can't create test dir");
		}

		factory.setChunkSize(chunk_size);

	}

	@AfterAll
	public static void teardown() throws IOException {
		/*
		if( file.exists()) {
			file.delete();
		}

		factory.disConnect();
		 */
	}

	@Test
	@Order(1)
	public void testCreateFile() throws IOException {
		file = (SftpFileSource) testDir.getChild("RamUnitTest.txt");
		if( file.exists()) {
			assertTrue(file.delete(),"Could not delete exesting file");
		}

		try (FileSourceRandomAccessStream ram = 
				new FileSourceRandomAccessStream(
						new SftpRandomAccessIoController(file), 
						"r")){
			assertTrue(false,"should generate an error opening a non existed file for read");
		} catch (Exception e) {
		}


		StringBuilder buf = new StringBuilder();

		try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new SftpRandomAccessIoController(file), "rw")) {
			assertTrue(file.exists(),"should create a new file when opening a non existed file for rw");
			while(
					ram.length()
					<targetFileSize) {
				ram.write(testData);
				buf.append(testDataString);
			}
		}

		file.refresh();



		byte buffer [] = new byte[(int)file.length()];
		try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new SftpRandomAccessIoController(file), "rw")) {
			ram.readFully(buffer);
		}



		String text = new String(buffer);
		if( !buf.toString().equals(text)) {
			System.out.println("len="+file.length());
			System.out.println("text="+text.length()+" ~"+text+"~");
			System.out.println("buf ="+buf.length()+" ~"+buf+"~");
		}
		assertEquals(buf.toString(),(text),"Did not read the expected value");
	}

	@Test
	@Order(2)
	public void testSeekAndRead() throws IOException {
		int len = (int)file.length();

		try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new SftpRandomAccessIoController(file), "rw")) {
			for(int idx = 0; idx<len; idx++) {
				int pos = idx % testData.length;
				int expect = testData[pos];				
				ram.seek(idx);
				int actual = ram.read();

				assertEquals(expect, actual,"Sequential forward seek read the wrong value after seek to "+idx+" pos="+pos);
			}

			for(int idx = len-1; idx>=0; idx--) {
				int pos = idx % testData.length;
				int expect = testData[pos];				
				ram.seek(idx);
				int actual = ram.read();
				assertEquals(expect, actual,"Sequential reverese seek read the wrong value after seek to "+idx+" pos="+pos);
			}

		}

		try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new SftpRandomAccessIoController(file), "rw")) {
			int tries = 0;
			Random r = new Random();
			int targetTries = 1000;

			while( tries++ < targetTries) {
				int idx = r.nextInt(len);
				int pos = idx % testData.length;
				int expect = testData[pos];				
				ram.seek(idx);
				int actual = ram.read();
				assertEquals(expect, actual,"Read the wrong value after seek to "+idx+" pos="+pos+" tries="+tries);
			}
		}
	}


	@Test
	@Order(3)
	public void testSetLength() throws IOException {
		int len = (int)file.length();
		assertTrue(len > 0," Test file is empty" );

		try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new SftpRandomAccessIoController(file), "rw")) {
			ram.setLength(len*2);
			assertEquals(len*2,file.length(),"Wrong value set len to *2" );
			try {
				ram.setLength(len);
				assertEquals(len,file.length(),"Wrong value set len to len" );
				ram.setLength(len/2);
				assertEquals(len/2,file.length(),"Wrong value set len to 1/2" );

			} catch (IOException e) {
				if( !e.getMessage().equals("Server does not support this function")) {
					throw e;
				}
			}

		}		
	}

	@Test
	@Order(4)
	public void compareWithJava() throws IOException {
		File jdir = new File("target").getAbsoluteFile();
		File jfile = new File(jdir,"RamFile.txt");
		if( jfile.exists()) {
			assertTrue(jfile.delete(),"Can't delet existing java file "+jfile);
		}

		if( file.exists()) {
			assertTrue(file.delete(),"Can't delet existing Sftp file "+file);
		}

		// make both file the same
		try(RandomAccessFile jram = new RandomAccessFile(jfile, "rw")){
			try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new SftpRandomAccessIoController(file), "rw")) {
				int cnt = 0;
				while(cnt < targetFileSize){
					jram.write(testData);
					ram.write(testData);
					cnt+= testData.length;
				}		

			}
		}

		boolean replay = false;
		if( !replay ) {
			testAndCompare(file,jfile);
		} else {
			replay(file,jfile);
		}
		assertTrue(jfile.delete(),"Can't delet existing java file "+jfile);



	}

	private void replay(SftpFileSource file2, File jfile) throws IOException {
		String steps[] = 
				(
						//"offset >= currentChunk.data.length  pos=1283 start=1067 Steps = 42\n"
						"	Seek,169,ram(0,169) jram(0,169)\n"
						+ "	SetLength,377,ram(169,169) jram(169,169)\n"
						+ "	Write,0,ram(169,179) jram(169,179)\n"
						+ "	Read,0,ram(179,189) jram(179,189)\n"
						+ "	Write,0,ram(189,199) jram(189,199)\n"
						+ "	Seek,105,ram(199,105) jram(199,105)\n"
						+ "	Write,0,ram(105,115) jram(105,115)\n"
						+ "	SetLength,503,ram(115,115) jram(115,115)\n"
						+ "	Read,0,ram(115,125) jram(115,125)\n"
						+ "	Read,0,ram(125,135) jram(125,135)\n"
						+ "	Seek,78,ram(135,78) jram(135,78)\n"
						+ "	Seek,565,ram(78,565) jram(78,565)\n"
						+ "	Seek,171,ram(565,171) jram(565,171)\n"
						+ "	Read,0,ram(171,181) jram(171,181)\n"
						+ "	Write,0,ram(181,191) jram(181,191)\n"
						+ "	Read,0,ram(191,201) jram(191,201)\n"
						+ "	Read,0,ram(201,211) jram(201,211)\n"
						+ "	Read,0,ram(211,221) jram(211,221)\n"
						+ "	Read,0,ram(221,231) jram(221,231)\n"
						+ "	Read,0,ram(231,241) jram(231,241)\n"
						+ "	Read,0,ram(241,251) jram(241,251)\n"
						+ "	Read,0,ram(251,261) jram(251,261)\n"
						+ "	SetLength,110,ram(261,261) jram(261,261)\n"
						+ "	Read,0,ram(261,271) jram(261,271)\n"
						+ "	SetLength,412,ram(271,271) jram(271,271)\n"
						+ "	Seek,101,ram(271,101) jram(271,101)\n"
						+ "	Write,0,ram(101,111) jram(101,111)\n"
						+ "	Read,0,ram(111,121) jram(111,121)\n"
						+ "	Read,0,ram(121,131) jram(121,131)\n"
						+ "	SetLength,29,ram(131,131) jram(131,131)\n"
						+ "	Write,0,ram(131,141) jram(131,141)\n"
						+ "	SetLength,681,ram(141,141) jram(141,141)\n"
						+ "	SetLength,1010,ram(141,141) jram(141,141)\n"
						+ "	Read,0,ram(141,151) jram(141,151)\n"
						+ "	SetLength,1067,ram(151,151) jram(151,151)\n"
						+ "	Read,0,ram(151,161) jram(151,161)\n"
						+ "	Write,0,ram(161,171) jram(161,171)\n"
						+ "	Read,0,ram(171,181) jram(171,181)\n"
						+ "	Seek,1100,ram(181,1100) jram(181,1100)\n"
						+ "	Read,0,ram(1100,1100) jram(1100,1100)\n"
						+ "	Seek,1283,ram(1100,1283) jram(1100,1283)\n"
						+ "	Write,0,ram(1283,0) jram(1283,0)\n"
						+ ""

						).split("\n");

		System.out.println("steps = "+steps.length);
		byte [] data = new byte[testData.length];
		byte [] jdata = new byte[testData.length];

		try(RandomAccessFile jram = new RandomAccessFile(jfile, "rw")){
			try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new SftpRandomAccessIoController(file), "rw")) {

				@SuppressWarnings("unused")
				Action last = null;
				int cnt = 0;
				for(String step : steps) {
					if( cnt == steps.length-1) {
						System.out.println("Ready");
					}
					String [] parts = step.split("[,]");
					Action a = Action.valueOf(parts[0].trim());
					int value = Integer.parseInt(parts[1].trim());
					switch (a) {
					case Read:
						int ji1=jram.read(jdata);System.out.println(""+cnt+" "+a+": jram len="+jram.length()+" ptr="+jram.getFilePointer());
						String js1=new String(jdata);
						int i1=ram.read(data);
						String s1=new String(data);
						if( ji1 != i1)  {
							System.out.println("i1="+i1+" ji1="+ji1);
						} else if(!s1.equals(js1)){
							System.out.println("s1="+s1+" js1="+js1);
						}

						System.out.println(""+cnt+" "+a+": ram len="+
								ram.length()+" ptr="+
								ram.getFilePointer());
						break;
					case Write:
						jram.write(testData);System.out.println(""+cnt+" "+a+": jram="+jram.length()+" "+jram.getFilePointer());
						System.out.println("before "+cnt+" "+a+": ram="+
								ram.length()+" "+
								ram.getFilePointer());

						ram.write(testData);													
						System.out.println("after "+cnt+" "+a+": ram="+
								ram.length()+" "+
								ram.getFilePointer());
						break;
					case Seek:
						jram.seek(value);System.out.println(""+cnt+" "+a+" "+value+" jram="+jram.length()+" "+jram.getFilePointer());
						ram.seek(value);
						System.out.println(""+cnt+" "+a+" "+value+": ram="+
								ram.length()+" "+
								ram.getFilePointer());
						break;
					case SetLength:
						try {
						ram.setLength(value);				
						System.out.println(""+cnt+" "+a+" "+value+": ram="+
								ram.length()
						+" "+ram.getFilePointer());
						jram.setLength(value);
						System.out.println(""+cnt+" "+a+" "+value+": jram="+jram.length()+" "+jram.getFilePointer());
						} catch(IOException e) {
							if( !e.getMessage().equals("Server does not support this function")) {
								throw e;
							}
						}
						
						break;
					default:
						break;
					}
					cnt++;
					last = a;
				}
				System.out.println("jram final length="+jram.length()+" ptr="+jram.getFilePointer());
				System.out.println("ram  final length="+ram.length() +" ptr="+ram.getFilePointer());
			}

		}
	}

	private void testAndCompare(SftpFileSource file, File jfile) throws IOException {

		Random r = new Random();
		byte [] data = new byte[testData.length];
		byte [] jdata = new byte[testData.length];
		List<Entry> list = new ArrayList<>();

		try(RandomAccessFile jram = new RandomAccessFile(jfile, "rw")){
			try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new SftpRandomAccessIoController(file), "rw")) {

				int actions = 50;
				while((--actions) >= 0) {
					int ai = r.nextInt(Action.values().length);
					Action action = Action.values()[ai];
					Entry entry = new Entry(action);

					list.add(entry);
					entry.jpointerBefore = jram.getFilePointer();
					entry.pointerBefore = ram.getFilePointer();

					switch(action) {
					case Read:
						int ji = jram.read(jdata);
						int i = -1;
						try {
							i  = ram.read(data);	
						} catch (Exception e) {
							print("Exception", list);
							throw e;
						}

						if( ji != i) {
							print("data Length",list);
						}
						assertEquals(ji, i,"Read count not the same");
						for (int idx = 0; idx < jdata.length; idx++) {
							if( jdata[idx]!= data[idx]) {
								print("data at "+idx,list);
							}
							assertEquals(jdata[idx], data[idx],"Data wrong at idx="+idx+" actions = "+list);
						}

						break;

					case Write:
						jram.write(testData);
						ram.write(testData);					
						break;
					case Seek:
						long len = ram.length();
						if( len == 0 ) {
							len = 10;
						}
						long bounds = (long)(len*1.5);
						long pos = r.nextInt((int)bounds);
						entry.value = pos;
						jram.seek(pos);
						ram.seek(pos);
						break;
					case SetLength:
						len = ram.length();
						if( len == 0 ) {
							len = 10;
						}

						bounds = (long)(len*1.5);
						pos = r.nextInt((int)bounds);
						entry.value = pos;
						
						try {
							ram.setLength(pos);
							jram.setLength(pos);
							if( jram.length() != ram.length()) {
								print("file length",list);
							}
							assertEquals(jram.length(), ram.length(),"Length not the same actions="+list);
							
						} catch (Exception e) {
							if( !e.getMessage().equals("Server does not support this function")) {
								print(e.toString(),list);
								throw e;
							}
						}


						break;
					default:throw new IOException("Unknown action="+action);
					}
					entry.jpointerAfter = jram.getFilePointer();
					entry.pointerAfter = ram.getFilePointer();
					if( jram.getFilePointer() != ram.getFilePointer()) {
						print("pointer",list);
					} 

					assertEquals(jram.getFilePointer(), ram.getFilePointer(),"Filepointer not the same actions="+list);

				}
				/**
				 * RandomAccessFile seems to write data to the storage immediately.
				 * The overhead of that is too high for sending data over the wire so
				 * SftpRam.. will delay potentially until close.  So there are times
				 * when the file lengths won;t match.  But, they should match once Sftp stream is closed.  
				 */
				if( jram.length()!= ram.length()) {
					print("End engths donte match", list);
				}
				assertEquals(jram.length(), ram.length(),"Length not the same actions="+list);
			} catch (Exception e) {
				print(e.toString(),list);
				throw e;
			}

		}
	}

	private void print(String type, List<Entry> list) {
		System.out.println(type+" Steps = "+list.size());
		for(Entry e: list) {
			System.out.println("\t"+e.action+","+e.value+",ram("+e.pointerBefore+","+e.pointerAfter+") jram("+e.jpointerBefore+","+e.jpointerAfter+")");
		}

	}
}
