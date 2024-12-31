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
 * ~version~V000.01.20-V000.01.19-V000.01.17-V000.01.16-V000.01.15-V000.01.09-V000.01.05-V000.01.02-V000.01.01-V000.00.05-V000.00.02-V000.00.01-V000.00.00-
 */
package us.bringardner.io.filesource.sftp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceFactory;


@TestMethodOrder(OrderAnnotation.class)
public abstract class FileSourceAbstractTestClass {

	
	public enum Permisions {
		OwnerRead('r'),
		OwnerWrite('w'),
		OwnerExecute('x'),

		GroupRead('r'),
		GroupWrite('w'),
		GroupExecute('x'),

		OtherRead('r'),
		OtherWrite('w'),
		OtherExecute('x');

	    public final char label;

	    private Permisions(char label) {
	        this.label = label;
	    }
	}

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
		assertTrue("Target file does not exist ("+
		target.getName()+")",
		target.exists()
		);

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

	// 336-518-5261 Alan's Diana

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
	@Order(1)
	public void testRoots() throws IOException {
		FileSource [] roots = factory.listRoots();
		assertNotNull("Roots are null",roots);
		assertTrue("No roots files ",roots.length>0);

	}

	@Test 
	@Order(2)
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
			assertTrue("Cannot create remote directory"+remoteDir,
					remoteDir.mkdirs()
					);			
		}
		traverseDir(remoteDir, null);



		for(FileSource source : cacheDir.listFiles()) {
			String nm = source.getName();
			FileSource dest = remoteDir.getChild(nm);
			copy(source, dest);
			compare("Copy to remote dir", source, dest);			
		}
		traverseDir(remoteDir, null);

		
		if(verbose) System.out.println("Rename files\n");
		for(FileSource remoteFile : remoteDir.listFiles()) {
			String fileName = remoteFile.getName();
			FileSource localFile = cacheDir.getChild(fileName);
			compare(fileName, localFile, remoteFile);
			FileSource remoteParent = remoteFile.getParentFile();
			
			FileSource renamedFile = remoteParent.getChild(fileName+".changed");
			
			renameAndValidate(remoteFile,renamedFile);
						
			compare(fileName, localFile, renamedFile);
			
			renameAndValidate(renamedFile,remoteFile);
			
			
			compare(fileName, localFile, remoteFile);
			
		}



		//  delete the roots files
		deleteAll(cacheDir);
		deleteAll(remoteDir);
		traverseDir(remoteDir, null);

	}

	private void renameAndValidate(FileSource source, FileSource target) throws IOException {
		assertTrue(
				source.renameTo(target)
				,"Can't rename "+source+" to "+target);			
		assertTrue(
				target.exists()
				,"New file does not exist after rename");
		assertFalse(
				source.exists()
				,"remoteFile still exists after rename");		
	}

	@Test 
	@Order(3)
	public void testPermissions() throws IOException {
		FileSource remoteDir = factory.createFileSource(remoteTestFileDirPath);
		if( !remoteDir.exists()) {
			assertTrue(remoteDir.mkdirs(),"Can't create dirs for "+remoteTestFileDirPath);
		}
		
		FileSource file = remoteDir.getChild("TestPermissions.txt");
		try(OutputStream out = file.getOutputStream()) {
			out.write("Put some data in the file".getBytes());
		}
		

		for(Permisions p : Permisions.values()) {
			//  if we turn off owner write we won't be able to turn it back on.
			if( p != Permisions.OwnerWrite) {
				changeAndValidatePermission(p,file);
			}
		}
		
		assertTrue(file.delete(),"Can't delete "+file);
		
	}

	private boolean setPermission(Permisions p, FileSource file,boolean b) throws IOException {
		boolean ret = false;
		switch (p) {
		case OwnerRead: 	ret = file.setOwnerReadable(b); break;
		case OwnerWrite:	ret = file.setOwnerWritable(b); break;
		case OwnerExecute:	ret = file.setOwnerExecutable(b); break;

		case GroupRead: 	ret = file.setGroupReadable(b); break;
		case GroupWrite:	ret = file.setGroupWritable(b); break;
		case GroupExecute:	ret = file.setGroupExecutable(b); break;

		case OtherRead: 	ret = file.setOtherReadable(b); break;
		case OtherWrite:	ret = file.setOtherWritable(b); break;
		case OtherExecute:	ret = file.setOtherExecutable(b); break;

		default:
			throw new RuntimeException("Invalid permision="+p);
		}
		
		return ret;
	}
	
	private boolean getPermission(Permisions p, FileSource file) throws IOException {
		boolean ret = false;
		switch (p) {
		case OwnerRead:    ret = file.canOwnerRead(); break;
		case OwnerWrite:   ret = file.canOwnerWrite(); break;
		case OwnerExecute: ret = file.canOwnerExecute(); break;
		
		case GroupRead:    ret = file.canGroupRead(); break;
		case GroupWrite:   ret = file.canGroupWrite(); break;
		case GroupExecute: ret = file.canGroupExecute(); break;
		
		case OtherRead:    ret = file.canOtherRead(); break;
		case OtherWrite:   ret = file.canOtherWrite(); break;
		case OtherExecute: ret = file.canOtherExecute(); break;
		
		default:
			throw new RuntimeException("Invalid permision="+p);			
		}
		return ret;
	}
	
	private void changeAndValidatePermission(Permisions p, FileSource file) throws IOException {
		
		//Get the current value		
		boolean b = getPermission(p, file);
		
		// toggle it 
		assertTrue(setPermission(p, file, !b),"set permission failed p="+p);
		boolean b2 = getPermission(p, file);
		assertEquals(b2, !b,"permision did not change p="+p);
		
		// Set it back
		assertTrue(setPermission(p, file, b),"reset permission failed p="+p);		
		assertEquals(getPermission(p, file), b,"permision did not change back to original p="+p);
		
		
	}

}
