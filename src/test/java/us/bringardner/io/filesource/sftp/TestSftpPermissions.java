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

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceGroup;
import us.bringardner.io.filesource.FileSourceUser;

/**
 * This code has the following requirements:
 * 1)  A real SSH server that you can control
 * 2)  Three valid users
 * 		A) user1 is in testgroup1 and testgroup2
 * 		B) user 2 is in testgroup2 only
 * 		C) user 3 in not in either test group 
 * 		 
 */
@TestMethodOrder(OrderAnnotation.class)
public class TestSftpPermissions {
	static int port = 22;
	static String user1 = "unittest1";
	static String user2 = "unittest2";
	static String user3 = "unittest3";
	
	static String password = "0000";

	static String testDirPath="TestFiles";
	static long targetSize = 10240;
	static SftpFileSourceFactory factory1;
	static SftpFileSourceFactory factory2;
	static SftpFileSourceFactory factory3;
	static FileSource remoteDir1;
	
	
	static int chunk_size = 100;
	static String testDataString = "0123456789";
	static byte [] testData = testDataString.getBytes();
	static FileSourceUser owner1 = new FileSourceUser(504, "unittest1", 505, "testgroup1");
	static FileSourceUser owner2 = new FileSourceUser(507, "unittest2", 506, "testgroup2");
	static FileSourceUser owner3 = new FileSourceUser(508, "unittest2", 12 , "everyone");
	static FileSourceGroup testGroup1 = new FileSourceGroup(505, "testgroup1");
	static FileSourceGroup testGroup2 = new FileSourceGroup(506, "testgroup2");
	

	
	@BeforeAll
	public static void setupBeforeAll() throws IOException {



		factory1 = startFactory(user1);
		factory2 = startFactory(user2);
		factory3 = startFactory(user3);

	}

	private static SftpFileSourceFactory startFactory(String user) throws IOException {
		SftpFileSourceFactory ret = new SftpFileSourceFactory();

		Properties p = ret.getConnectProperties();

		p.setProperty("user", user);
		p.setProperty("host", "localhost");
		p.setProperty("port", ""+port);
		p.setProperty("password",password);
		ret.setConnectionProperties(p);		

		assertTrue(ret.connect(),"Factory did not start.");


		FileSource dir = ret.createFileSource(testDirPath);
		if( !dir.exists()) {
			assertTrue(
					dir.mkdirs(),
					user+" Cannot create remote directory"+dir
					);			
		}

		ret.setChunkSize(chunk_size);
		if( user.equals(user1)) {
			remoteDir1 = dir;
		}
		
		return ret;
	}


	@AfterAll
	public static void teardownAfterAll() throws IOException {
		deleteAll(remoteDir1);

		factory1.disConnect();
		factory2.disConnect();
		factory3.disConnect();

	}

	private static void deleteAll(FileSource dir) throws IOException {
		if( dir.exists()) {
			if( dir.isDirectory()) {
				for(FileSource f : dir.listFiles()) {
					deleteAll(f);
				}
			}
			assertTrue(dir.delete(),"Can't delete "+dir.getAbsolutePath());
		}
	}

	public FileSource createFile(FileSource dir, String name) throws IOException {
		//FileSource rwxrw_r__ = remoteDir.getChild("rwxrw_r__.txt");
		FileSource file = dir.getChild(name);
		if( !file.exists()) {
			file.createNewFile();
		}

		if( name.charAt(0)=='r') {file.setOwnerReadable(true);} else {file.setOwnerReadable(false);}
		if( name.charAt(1)=='w') {file.setOwnerWritable(true);} else {file.setOwnerWritable(false);}
		if( name.charAt(2)=='x') {file.setOwnerExecutable(true);} else {file.setOwnerExecutable(false);}

		if( name.charAt(3)=='r') {file.setGroupReadable(true);} else {file.setGroupReadable(false);}
		if( name.charAt(4)=='w') {file.setGroupWritable(true);} else {file.setGroupWritable(false);}
		if( name.charAt(5)=='x') {file.setGroupExecutable(true);} else {file.setGroupExecutable(false);}

		if( name.charAt(6)=='r') {file.setOtherReadable(true);} else {file.setOtherReadable(false);}
		if( name.charAt(7)=='w') {file.setOtherWritable(true);} else {file.setOtherWritable(false);}
		if( name.charAt(8)=='x') {file.setOtherExecutable(true);} else {file.setOtherExecutable(false);}

		return file;
	}

	@Test
	@Order(1)
	public void testPermissions() throws IOException, NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
		
	
		doTest("rwxrwxrwx.txt",true,true,true,true,true,true,null);
		doTest("rwxr_xrwx.txt",true,true,true,false,true,true,null);
		doTest("rwx__xrwx.txt",true,true,false,false,true,true,null);
		doTest("rwx_wxrwx.txt",true,true,false,true,true,true,null);
		//user1 is in testgroup1 AND testgroup2
		// user2 is only in testgroup2
		doTest("rwx__xrwx.txt",true,true,true,true,true,true,testGroup1);
		doTest("rwx__xrwx.txt",true,true,false,false,true,true,testGroup2);
		doTest("rwxr_xrwx.txt",true,true,true,false,true,true,testGroup2);
		doTest("rwx_wxrwx.txt",true,true,false,true,true,true,testGroup2);
		
		doTest("rwxrwx_wx.txt",true,true,true,true,
				false,true,testGroup2);
		doTest("rwxrwx__x.txt",true,true,true,true,
				false,false,testGroup2);
		doTest("rwxrwxr_x.txt",true,true,true,true,
				true,false,testGroup2);
	}

	private void doTest(String name, 
			boolean userRead, boolean userWrite, 
			boolean groupRead, boolean groupWrite,
			boolean otherRead, boolean otherWrite,
			FileSourceGroup group) throws IOException {
		FileSource file1 = createFile(remoteDir1, name);
		if( group != null ) {
			assertTrue(file1.setGroup(group), "Can't set grop "+group+" on "+file1);
		}
		
		assertEquals(userRead,file1.canRead(), "User can't read as expected for "+file1);
		assertEquals(userWrite,file1.canWrite(), "User can't write as expected for "+file1);
		
		// same file but different user
		String filePath = file1.getAbsolutePath();
		FileSource file2 = factory2.createFileSource(filePath);
		assertEquals(groupRead, 
				file2.canRead(),  
				"Group can't read as expected for "+file2);
		assertEquals(groupWrite,
				file2.canWrite(), 
				"Group can't write as expected for "+file2);
		
		// always test as testgroup2 so user3 is other for file3
		assertTrue(file1.setGroup(testGroup2), "Can't set grop "+group+" on "+file1);
		
		
		
		FileSource file3 = factory3.createFileSource(filePath);
		
		assertEquals(otherRead, 
				file3.canRead(),  
				"Other can't read as expected for "+file3);
		assertEquals(otherWrite,
				file3.canWrite(), 
				"Other can't write as expected for "+file3);
		
		
		
		
		assertTrue(file1.delete(), "Can't delete "+file1);
	}

	
}
