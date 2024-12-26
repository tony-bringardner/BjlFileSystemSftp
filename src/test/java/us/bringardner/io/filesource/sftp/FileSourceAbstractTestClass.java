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
 * ~version~V000.01.06-V000.01.20-V000.01.19-V000.01.17-V000.01.16-V000.01.15-V000.01.09-V000.01.05-V000.01.02-V000.01.01-V000.00.05-V000.00.02-V000.00.01-V000.00.00-
 */
package us.bringardner.io.filesource.sftp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceFactory;


public abstract class FileSourceAbstractTestClass {

	interface TestAction {
		void doSomthing(FileSource dir);
	}

	public static String localTestFileDirPath ;
	public static String remoteTestFileDirPath ;
	public static String localCacheDirPath;	
	public static FileSourceFactory factory;
	public static boolean verbose = false;



	@AfterAll
	static void tearDownAfterAll()  {
		if( factory != null ) {
			try {
				factory.disConnect();
			} catch (Throwable e) {
			}
		}
	}

	public static void traverseDir(FileSource dir,TestAction action) throws IOException {
		if(verbose) System.out.println(format(dir));
		if( action != null ) {
			action.doSomthing(dir);
		}
		if( dir.isDirectory()) {
			FileSource [] kids = dir.listFiles();
			if( kids != null ) {
				for(FileSource file : kids) {
					traverseDir(file,action);
				}
			}
		}		
	}

	public static void deleteAll(FileSource file) throws IOException {
		if( file.isDirectory()) {
			for(FileSource child : file.listFiles()) {
				deleteAll(child);
			}
		}
		assertTrue("Can't delete "+file,
				file.delete()
				);
	}

	public static String format(FileSource dir) throws IOException {
		String ret = (String.format("factory=%s type=%s exists=%s path=%s read=%s write=%s size=%d",
				dir.getFileSourceFactory().getTypeId(),
				dir.isFile()?"File":dir.isDirectory()?"Dir":"Undefined",
						dir.exists() ? "true":"false",
								dir.getAbsolutePath(),
								dir.canRead()?"true":"false",
										dir.canWrite()?"true":"false",
												dir.length()
				)
				);

		return ret;

	}

	public static void compare(String name,FileSource source, FileSource target) throws IOException {
		assertTrue("Source file does not exist ("+source.getName()+")",source.exists());
		assertTrue("Target file does not exist ("+target.getName()+")",target.exists());

		assertEquals(name+" are not the same type",source.isDirectory(), target.isDirectory());

		if( source.isDirectory()) {
			FileSource [] kids1 = source.listFiles();
			FileSource [] kids2 = target.listFiles();
			assertEquals(name+" does not have the same number of kids",kids1.length,kids2.length);
			for(int idx=0;idx <  kids1.length; idx++ ) {
				compare(name,kids1[idx],kids2[idx]);
			}

		} else {
			assertEquals(name+" lens are not eq",source.length(), target.length());
			try(InputStream sourceIn = source.getInputStream()) {
				try(InputStream targetIn  = target.getInputStream()) {
					compare(name,sourceIn,targetIn);
				}
			}
		}
	}

	/**
	 * Compare the bytes of two input streams
	 * 
	 * @param in1
	 * @param in2
	 * @throws IOException
	 */
	public static void compare(String name,InputStream in1, InputStream in2) throws IOException {
		//  in1 & in2 will be closed by the java try / auto close in the calling function
		// use a small buffer to get multiple reads 
		BufferedInputStream bin1 = new BufferedInputStream(in1);
		BufferedInputStream bin2 = new BufferedInputStream(in2);

		int ch = bin1.read();
		int pos = 0;
		while( ch > 0) {
			assertEquals(name+" compare pos="+pos,ch, bin2.read());
			pos++;
			ch = bin1.read();				
		}
		assertEquals(name+" compare pos="+pos,ch, bin2.read());

	}

	public static void copy(FileSource from, FileSource to) throws IOException {
		FileSource parent = to.getParentFile();
		if( parent != null && !parent.exists()) {
			parent.mkdirs();
		} 
		if( from.isDirectory()) {
			FileSource [] kids = from.listFiles();
			if( kids != null ) {
				for(FileSource f : kids) {
					copy(f,to.getChild(f.getName()));
				}
			}
		} else {
			try(InputStream in = from.getInputStream()) {
				try(OutputStream out = to.getOutputStream()) {
					copy(in,out);		
				}
			}
			
		}
	}


	public static void copy(InputStream in, OutputStream out) throws IOException {
		// use a small buffer to get multiple reads 
		byte [] data = new byte[1024];
		int got = 0;

		try {
			while( (got=in.read(data)) >= 0) {
				if( got > 0 ) {
					out.write(data,0,got);
				}
			}

		} finally {
			try {
				out.close();
			} catch (Exception e) {
			}
			try {
				in.close();
			} catch (Exception e) {
			}

		}
	}

	@Test
	public void testRoots() throws IOException {
		FileSource [] roots = factory.listRoots();
		assertNotNull("Roots are null",roots);
		assertTrue("No roots files ",roots.length>0);

	}

	@Test 
	public void replicateTestDir() throws IOException {
		FileSource _localDir = FileSourceFactory.getDefaultFactory().createFileSource(localTestFileDirPath);
		assertTrue("local test dir does not exist ="+_localDir,_localDir.isDirectory());

		FileSource cacheDir = FileSourceFactory.getDefaultFactory().createFileSource(localCacheDirPath);
		if( cacheDir.exists()) {
			deleteAll(cacheDir);			
		}
		assertFalse("local cache dir already exists ="+cacheDir,cacheDir.exists());

		//  Make a copy of the local test directory
		copy(_localDir,cacheDir);

		FileSource remoteDir = factory.createFileSource(remoteTestFileDirPath);
		traverseDir(remoteDir, null);
		if( !remoteDir.exists()) {
			remoteDir.mkdir();
		}
		traverseDir(remoteDir, null);



		for(FileSource source : cacheDir.listFiles()) {
			FileSource dest = remoteDir.getChild(source.getName());
			copy(source, dest);
			compare("Copy to remote dir", source, dest);			
		}
		traverseDir(remoteDir, null);

		if(verbose) System.out.println("Test set readable and set writable\\n");
		for(FileSource file : remoteDir.listFiles()) {
			file.setReadable(false);
			traverseDir(file, null);
			String content = null;
			try {
				try(InputStream in = file.getInputStream()) {	
					if( in != null ) {
						assertTrue("getInputStream should throw permission denied",false);
					}
				}
			} catch (Throwable e) {
				file.setReadable(true);
				traverseDir(file, null);
				try {
					try(InputStream in = file.getInputStream()) {	
						if( in == null ) {
							assertTrue("getInputStream should return a stream",false);
						}	else {
							content = new String(in.readAllBytes());
							assertEquals("Content len does not match file len",content.length(), file.length());
						}
					}
				} catch (Throwable e2) {
					e2.printStackTrace();
					assertTrue("Unexpected error "+e2, false);
				}
			}
			file.setWritable(false);
			traverseDir(file, null);
			try {
				try(OutputStream out = file.getOutputStream()) {	
					if( out == null ) {
						assertTrue("getOutputStream should return throw permission denied",false);
					}	
				}
			} catch (Throwable e2) {				
			}
			
			file.setWritable(true);
			traverseDir(file, null);
			try(OutputStream out = file.getOutputStream()) {
				out.write(content.getBytes());
			}
			assertEquals("Content len does not match file len",content.length(), file.length());
		}

		if(verbose) System.out.println("Rename files\n");
		for(FileSource remoteFile : remoteDir.listFiles()) {
			String fileName = remoteFile.getName();
			FileSource localFile = cacheDir.getChild(fileName);
			compare(fileName, localFile, remoteFile);
			FileSource remoteParent = remoteFile.getParentFile();
			
			FileSource renamedFile = remoteParent.getChild(fileName+".changed");
			assertTrue(remoteFile.renameTo(renamedFile),"Can't rename "+remoteFile+" to "+renamedFile);
			
			compare(fileName, localFile, renamedFile);
			assertTrue(renamedFile.exists(),"New file does not exist after rename");
			assertFalse(remoteFile.exists(),"remoteFile still exists after rename");
			assertTrue(renamedFile.renameTo(remoteFile),"Can't rename back to original. "+renamedFile+" to "+remoteFile);
			compare(fileName, localFile, remoteFile);
			
		}



		//  delete the roots files
		deleteAll(cacheDir);
		deleteAll(remoteDir);
		traverseDir(remoteDir, null);

	}


}
