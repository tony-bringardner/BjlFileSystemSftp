/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 * ~version~
 */
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

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceRandomAccessStream;


/**
 * IRandomAccessStream is an interface that defines a random access file.  
 * It is intended to to reproduce java.io.RandomAccessFile
 * The implementation is divided into a logic layer and a data management layer (IRandomAccessIoController)
 * 
 * The implementation is divided into three parts;
 * 
 * 
 * 1)	File pointer management done by FileSourceRandomAccessStream
 * 2)	Functional implementation done by AbstractRandomAccessStream
 * 			functions like readDouble,writeDouble,...
 * 3)	Data manipulation: most of the work is done by AbstractRandomAccessIoController
 * 			with the help of a FileSourceFactory specific worker, SftpRandomAccessIoController. 
 * 
 */

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
	static String remoteTestFileDirPath = "SftpUnitTest";
	static SftpFileSourceFactory factory;
	static int chunk_size = 100;
	static long targetFileSize=500;
	static String testDataString = "0123456789";
	static byte [] testData = testDataString.getBytes();
	static SftpFileSource file;
	static SftpFileSource testDir;
	
	
	static int port = 22;
	static String user = "unittest1";
	static String password = "0000";
	static String host = "localhost";
	static int timeout = 5000;
	private static FileSource remoteDir;


	@BeforeAll
	public static void setup() throws IOException {


	
		factory = new SftpFileSourceFactory();

		Properties p = factory.getConnectProperties();
	
		p.setProperty("user", user);
		p.setProperty("host", host);
		p.setProperty("port", ""+port);
		p.setProperty("password",""+password);
		factory.setConnectionProperties(p);		

		assertTrue(factory.connect(),"Factory did not start.");

		remoteDir = factory.createFileSource(remoteTestFileDirPath);
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
		deleteAll(remoteDir);
		factory.disConnect();
		 
	}

	private static void deleteAll(FileSource dir) throws IOException {
		if( dir.isDirectory()) {
			for(FileSource f : dir.listFiles()) {
				deleteAll(f);
			}
		}
		assertTrue(dir.delete(),"Can't delete "+dir.getAbsolutePath());
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
						 "	SetLength,353,ram(0,0) jram(0,0)\n"
						 + "	Write,0,ram(0,10) jram(0,10)\n"
						 + "	Read,0,ram(10,20) jram(10,20)\n"
						 + "	Write,0,ram(20,30) jram(20,30)\n"
						 + "	SetLength,254,ram(30,30) jram(30,30)\n"
						 + "	Seek,32,ram(30,32) jram(30,32)\n"
						 + "	Write,0,ram(32,42) jram(32,42)\n"
						 + "	Seek,2,ram(42,2) jram(42,2)\n"
						 + "	Write,0,ram(2,12) jram(2,12)\n"
						 + "	SetLength,342,ram(12,12) jram(12,12)\n"
						 + "	Seek,242,ram(12,242) jram(12,242)\n"
						 + "	SetLength,325,ram(242,242) jram(242,242)\n"
						 + "	SetLength,216,ram(242,216) jram(242,216)\n"
						 + "	SetLength,23,ram(216,23) jram(216,23)\n"
						 + "	Seek,18,ram(23,18) jram(23,18)\n"
						 + "	Seek,11,ram(18,11) jram(18,11)\n"
						 + "	Seek,6,ram(11,6) jram(11,6)\n"
						 + "	Write,0,ram(6,16) jram(6,16)\n"
						 + "	SetLength,0,ram(16,0) jram(16,0)\n"
						 + "	Seek,9,ram(0,9) jram(0,9)\n"
						 + "	Seek,8,ram(9,8) jram(9,8)\n"
						 + "	Seek,13,ram(8,13) jram(8,13)\n"
						 + "	SetLength,1,ram(13,1) jram(13,1)\n"
						 + "	Seek,0,ram(1,0) jram(1,0)\n"
						 + "	SetLength,0,ram(0,0) jram(0,0)\n"
						 + "	Seek,2,ram(0,2) jram(0,2)\n"
						 + "	Write,0,ram(2,12) jram(2,12)\n"
						 + "	Read,0,ram(12,12) jram(12,12)\n"
						 + "	Write,0,ram(12,22) jram(12,22)\n"
						 + "	Seek,20,ram(22,20) jram(22,20)\n"
						 + "	Read,0,ram(20,22) jram(20,22)\n"
						 + "	Read,0,ram(22,22) jram(22,22)\n"
						 + "	Read,0,ram(22,22) jram(22,22)\n"
						 + "	Write,0,ram(22,32) jram(22,32)\n"
						 + "	Read,0,ram(32,32) jram(32,32)\n"
						 + "	Write,0,ram(32,42) jram(32,42)\n"
						 + "	Seek,61,ram(42,61) jram(42,61)\n"
						 + "	Seek,55,ram(61,55) jram(61,55)\n"
						 + "	SetLength,41,ram(55,41) jram(55,41)\n"
						 + "	Read,0,ram(41,41) jram(41,41)\n"
						 + "	Seek,32,ram(41,32) jram(41,32)\n"
						 + "	SetLength,7,ram(32,7) jram(32,7)\n"
						 + "	Seek,2,ram(7,2) jram(7,2)\n"
						 + "	SetLength,7,ram(2,2) jram(2,2)\n"
						 + "	Seek,7,ram(2,7) jram(2,7)\n"
						 + "	Seek,3,ram(7,3) jram(7,3)\n"
						 + "	Read,0,ram(3,0) jram(3,0)"
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
						byte[] td = new byte[jdata.length];
						int ji1=jram.read(td);System.out.println(""+cnt+" "+a+": jram len="+jram.length()+" ptr="+jram.getFilePointer());
						String js1=new String(td);
						byte[] td2 = new byte[data.length];
						int i1=ram.read(td2);
						String s1=new String(td2);
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
						ram.setLength(value);				
						System.out.println(""+cnt+" "+a+" "+value+": ram len="+
								ram.length()
						+" ptr="+ram.getFilePointer());
						jram.setLength(value);
						System.out.println(""+cnt+" "+a+" "+value+": jram len="+jram.length()+" ptr="+jram.getFilePointer());
						
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
