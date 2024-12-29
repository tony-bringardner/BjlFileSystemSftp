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
 * ~version~V000.01.06-V000.01.05-V000.00.02-V000.01.01-V000.01.00-V000.00.01-V000.00.00-
 */
package us.bringardner.io.filesource.sftp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import javax.swing.ProgressMonitor;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import us.bringardner.core.BaseObject;
import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceFactory;
import us.bringardner.io.filesource.FileSourceFilter;
import us.bringardner.io.filesource.ISeekableInputStream;
import us.bringardner.io.filesource.fileproxy.FileProxy;

public class SftpFileSource extends BaseObject implements FileSource {


	private static final long serialVersionUID = 1L;


	private class SftpOutputStream extends OutputStream {

		private ChannelSftp mySftp;
		private OutputStream out;

		SftpOutputStream (boolean append) throws IOException {
			attr = null;
			exists = null;

			try {
				mySftp = (ChannelSftp) factory.getSession().openChannel("sftp");
				mySftp.connect();
				if( append ) {
					this.out = mySftp.put(path, ChannelSftp.APPEND);
				} else {
					this.out = mySftp.put(path, ChannelSftp.OVERWRITE);
				}

			} catch (JSchException e) {
				throw new IOException(e);
			} catch (SftpException e) {
				throw new IOException(e);
			}

		}
		@Override
		public void write(int b) throws IOException {
			out.write(b);
		}

		@Override
		public void close() throws IOException {
			try {
				out.close();
			} catch(Throwable e) {}
			try {
				mySftp.disconnect();
				clearAttr();
			} catch (Exception e) {
			}

		}

		@Override
		public void flush() throws IOException {
			out.flush();
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			out.write(b, off, len);
			//attr = null;
		}

		@Override
		public void write(byte[] b) throws IOException {
			out.write(b);
			//attr = null;
		}


	}

	private class SftpInputStream extends InputStream {

		private ChannelSftp mySftp;
		private InputStream in;

		SftpInputStream() throws IOException {
			attr = null;
			exists = null;

			try {
				mySftp = (ChannelSftp) factory.getSession().openChannel("sftp");
				mySftp.connect();
				in = mySftp.get(path);

			} catch (JSchException e) {
				throw new IOException(e);
			} catch (SftpException e) {
				throw new IOException(e);
			}
		}

		public SftpInputStream(long skipTo) throws IOException {
			attr = null;
			exists = null;

			try {
				mySftp = (ChannelSftp) factory.getSession().openChannel("sftp");
				mySftp.connect();
				in = mySftp.get(path,null,skipTo);

			} catch (JSchException e) {
				throw new IOException(e);
			} catch (SftpException e) {
				throw new IOException(e);
			}

		}

		@Override
		public int read() throws IOException {
			return in.read();
		}

		@Override
		public int available() throws IOException {
			return in.available();
		}

		@Override
		public void close() throws IOException {
			try {
				in.close();
			} catch(Throwable e) {}
			mySftp.disconnect();
		}

		@Override
		public synchronized void mark(int arg0) {

			in.mark(arg0);
		}

		@Override
		public boolean markSupported() {

			return in.markSupported();
		}

		@Override
		public int read(byte[] arg0, int arg1, int arg2) throws IOException {

			return in.read(arg0, arg1, arg2);
		}

		@Override
		public int read(byte[] arg0) throws IOException {

			return in.read(arg0);
		}

		@Override
		public synchronized void reset() throws IOException {

			in.reset();
		}

		@Override
		public long skip(long arg0) throws IOException {

			return in.skip(arg0);
		}

	}

	private String name;
	private SftpATTRS attr;
	private Boolean exists;
	private SftpFileSource[] kids;
	private String path;
	private SftpFileSourceFactory factory;
	private Boolean isLink;


	private SftpFileSource parent;
	private FileSource linkedTo;
	private UserPrincipal owner;
	private GroupPrincipal group;

	/**
	 * Construct a new SftpFileSource from 'factory' 
	 * 
	 * @param factory
	 * @param path
	 */
	SftpFileSource(SftpFileSourceFactory factory, String path) {
		this.factory = factory;
		this.path = path;
		int idx = path.lastIndexOf('/');
		if( idx == 0) {
			// path is "/" 
			name = "/";
		} else {
			name = path.substring(idx+1);
		}
	}

	/**
	 * Construct a new SftpFileSource as a child of 'parent'.
	 * 
	 * @param factory
	 * @param parent
	 * @param name: this is the child file name (appended to the parent path)  
	 * @throws IOException
	 */
	private SftpFileSource(SftpFileSourceFactory factory, SftpFileSource parent, String name) throws IOException {
		this.parent = parent;
		this.factory = factory;
		this.name = name;
		this.path = parent.getCanonicalPath()+"/"+name;
		this.parent.addChild(this);
	}

	/**
	 * Construct a SftpFileSource as a child of parent .
	 * 
	 * @param factory
	 * @param parent
	 * @param entry : The list entry for this file.
	 */
	private SftpFileSource(SftpFileSourceFactory factory, SftpFileSource parent, LsEntry entry) {
		this.factory = factory;

		this.parent = parent;
		this.name = entry.getFilename();
		this.attr = entry.getAttrs();
		this.path = parent.path+"/"+this.name;
		//  We have a list entry so this file MUST exists.
		this.exists = (true);
	}

	private synchronized SftpFileSource[] getKids() throws IOException {
		if( kids == null ) {
			List<FileSource> list = new ArrayList<FileSource>();
			if( !isFile() ) {
				// if it's not a file it may be a directory or a new / non existing entry
				try {
					Vector<LsEntry> ls = factory.ls(path);
					for (LsEntry e : ls) {
						//System.out.println("name ="+e.getFilename());
						if( ! (e.getFilename().equals(".") || e.getFilename().equals(".."))) {
							list.add(new SftpFileSource(factory, this,e));
						}
					}
				} catch (SftpException e) {
					if( e.getMessage().equals("No such file") || e.getMessage().endsWith("not a valid file path")) {
						exists = (false);
					} else {
						throw new IOException(e);
					}
				}
				kids = list.toArray(new SftpFileSource[list.size()]);
			}
		}

		return kids;
	}

	private synchronized void clearAttr()  {
		attr = null;
	}

	private synchronized SftpATTRS getAttr() throws IOException {
		if( attr == null ) {
			try {
				attr = factory.lstat(path);
				exists = (true);
			} catch (SftpException e) {
				if( e.getMessage().equals("No such file") || e.getMessage().endsWith("not a valid file path")) {
					exists = (false);
				} else {
					throw new IOException(e);
				}
			}
		}
		return attr;
	}




	@Override
	public int compareTo(Object o) {
		try {
			return getCanonicalPath().compareTo(o.toString());
		} catch (IOException e) {
			e.printStackTrace();
			return -1;
		}
	}

	@Override
	public long getCreateDate() throws IOException {
		return 0;
	}

	@Override
	public String getContentType() {
		return FileProxy.getContentType(getName());
	}

	@Override
	public boolean canRead() throws IOException {
		return (getAttr().getPermissionsString().charAt(1) == 'r');//-rw-r--r--;
	}

	@Override
	public boolean canWrite() throws IOException {
		return (getAttr().getPermissionsString().charAt(2) == 'w');//-rw-r--r--;
	}

	@Override
	public boolean canOwnerRead() throws IOException {
		return canRead();
	}

	@Override
	public boolean canOwnerWrite() throws IOException {
		return canWrite();
	}

	@Override
	public boolean canOwnerExecute() throws IOException {
		return (getAttr().getPermissionsString().charAt(3) == 'x');//drwxr--r--;
	}

	@Override
	public boolean canGroupRead() throws IOException {
		return (getAttr().getPermissionsString().charAt(4) == 'r');//d---rwx---;
	}

	@Override
	public boolean canGroupWrite() throws IOException {
		return (getAttr().getPermissionsString().charAt(5) == 'w');//d---rwx---;
	}

	@Override
	public boolean canGroupExecute() throws IOException {
		return (getAttr().getPermissionsString().charAt(6) == 'x');//d---rwx---;
	}

	@Override
	public boolean canOtherRead() throws IOException {
		return (getAttr().getPermissionsString().charAt(7) == 'r');//d------rwx;
	}

	@Override
	public boolean canOtherWrite() throws IOException {
		return (getAttr().getPermissionsString().charAt(8) == 'w');//d------rwx;
	}

	@Override
	public boolean canOtherExecute() throws IOException {
		return (getAttr().getPermissionsString().charAt(9) == 'x');//d------rwx;
	}

	@Override
	public boolean createNewFile() throws IOException {
		// not supported
		return false;
	}

	@Override
	public FileSource getChild(String path) throws IOException {
		path = path.replace('\\', '/');
		if( path.startsWith("/")) {
			path = path.substring(1);
		}
		if( path.endsWith("/")) {
			path = path.substring(0, path.length()-1);
		}
		String parts [] = path.split("[/]");
		SftpFileSource ret = findChild(parts[0]);
		// does it exist?

		ret = new SftpFileSource(factory,this,parts[0]);
		for(int idx=1; idx < parts.length; idx++ ) {
			ret = new SftpFileSource(factory,ret,parts[idx]);
		}

		return ret;
	}


	private void addChild(SftpFileSource child) throws IOException {
		kids = null;
		/*
		getKids();
		SftpFileSource tmp[] = new SftpFileSource[kids.length+1];
		for (int idx = 0; idx < kids.length; idx++) {
			tmp[idx] = kids[idx];
		}
		tmp[kids.length] = child;
		kids = tmp;
		*/
	}

	private SftpFileSource findChild(String name) throws IOException {
		SftpFileSource ret = null;
		for (SftpFileSource f : getKids()) {
			if( f.getName().equals(name) ) {
				ret = f;
				break;
			}
		}

		return ret;
	}

	@Override
	public synchronized boolean delete() throws IOException {
		boolean ret = false;
		try {
			ChannelSftp sftp = factory.getSftp_();
			if( isDirectory() ) {
				sftp.rmdir(path);
			} else {
				sftp.rm(path);
			}
			attr = null;
			exists = null;
			FileSource p = getParentFile();
			if( p != null ) {

				if (p instanceof SftpFileSource) {
					((SftpFileSource) p).kids = null;					
				}
			}
			ret = true;
		} catch (SftpException e) {
			throw new IOException(e);
		}
		return ret;
	}

	@Override
	public synchronized  boolean exists() throws IOException {
		if( exists == null ) {
			getAttr();
		}
		return exists.booleanValue();
	}

	@Override
	public FileSourceFactory getFileSourceFactory() {
		return factory;
	}

	@Override
	public String getAbsolutePath() {
		return path;
	}

	@Override
	public String getCanonicalPath() throws IOException {
		return path;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getParent() {
		String ret = null;
		if( !path.equals("/")) {
			int idx = path.lastIndexOf("/");
			if( idx > 0 ) {
				ret = path.substring(0,idx);
			}
		}
		return ret;
	}

	@Override
	public  synchronized FileSource getParentFile() {
		if( parent == null ) {
			String pp = getParent();
			if( pp != null ) {
				parent = new SftpFileSource(factory, pp);
			}
		}

		return parent;
	}

	@Override
	public boolean isChildOfMine(FileSource child) throws IOException {
		return child.getAbsolutePath().startsWith(getAbsolutePath());
	}

	@Override
	public synchronized  boolean isDirectory() throws IOException {
		SftpATTRS a = getAttr();

		return a == null ? false :  a.isDir();
	}

	@Override
	public synchronized  boolean isFile() throws IOException {
		SftpATTRS a = getAttr();

		return a == null ? false :  !a.isDir();
	}

	@Override
	public synchronized  long length() throws IOException {
		if( exists()) {
			return getAttr().getSize();
		} else {
			return 0;
		}
	}

	@Override
	public synchronized  long lastModified() throws IOException {
		if( exists()) {
			return ((long)getAttr().getMTime())*1000l;
		} else {
			return 0;
		}
	}

	@Override
	public String[] list() throws IOException {
		FileSource[] list = listFiles();
		String ret [] = new String[list.length];
		for (int idx = 0; idx < ret.length; idx++) {
			ret[idx] = list[idx].getName();
		}

		return ret;
	}

	@Override
	public String[] list(FileSourceFilter filter) throws IOException {
		FileSource [] list = listFiles(filter);
		String ret [] = new String[list.length];
		for (int idx = 0; idx < ret.length; idx++) {
			ret[idx] = list[idx].getName();
		}

		return ret;
	}

	@Override
	public  synchronized FileSource[] listFiles() throws IOException {
		return getKids();
	}

	@Override
	public synchronized  FileSource[] listFiles(FileSourceFilter filter) throws IOException {
		List<FileSource> ret = new ArrayList<FileSource>();
		FileSource[] list = listFiles();
		for (FileSource f : list) {
			if( filter.accept(f)) {
				ret.add(f);
			}
		}
		return ret.toArray(new FileSource[ret.size()]);
	}

	@Override
	public synchronized  boolean mkdir() throws IOException {
		boolean ret = false;
		try {
			factory.getSftp_().mkdir(path);
			attr =  null;
			exists = null;
			ret = exists();
		} catch (SftpException e) {
			throw new IOException(e);
		}

		return ret;
	}

	@Override
	public  synchronized boolean mkdirs() throws IOException {
		boolean ret = exists();
		if( !ret ) {
			FileSource p = getParentFile();
			if( p != null ) {
				if( p.mkdirs()) {
					ret = mkdir();
				}
			} else {
				ret = mkdir();
			}

		}
		return ret;
	}

	@Override
	public synchronized  boolean renameTo(FileSource dest) throws IOException {
		String myName = getCanonicalPath();
		String yourName = dest.getCanonicalPath();
	
		boolean ret = false;
		if(exists() && 
				!dest.exists()				
				) {

			try {
				if (dest instanceof SftpFileSource) {
					SftpFileSource fs = (SftpFileSource) dest;


					if( !(myName.equals("/") || yourName.equals("/") || myName.equals(yourName))) {
						factory.getSftp_().rename(myName, yourName);
						ret = true;
						fs.attr =attr = null;
						fs.exists = exists = null;
						fs.kids = kids = null;
						((SftpFileSource)getParentFile()).kids = null;
					}	
				}
			} catch (SftpException e) {
				throw new IOException(e);
			}
		}
		return ret;
	}

	@Override
	public synchronized  void setLastModifiedTime(long time) throws IOException {
		try {
			factory.getSftp_().setMtime(path, (int) (time / 1000));
			attr = null;
			getAttr();
		} catch (SftpException e) {
			throw new IOException(e);
		}
	}

	@Override
	public boolean setReadOnly() throws IOException {
		// not supported
		return false;
	}

	@Override
	public InputStream getInputStream() throws FileNotFoundException,IOException {
		return new SftpInputStream();
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return new SftpOutputStream(false);

	}

	@Override
	public OutputStream getOutputStream(boolean append)	throws IOException {

		return new SftpOutputStream(append);

	}

	@Override
	public URL toURL() throws MalformedURLException {
		URL ret = null;

		String path = null;

		try {
			path = getCanonicalPath();
		} catch (IOException e) {
			throw new MalformedURLException("Can't get path");
		}
		if( path == null ) {
			path = "/";
		} else {
			if( !path.startsWith("/")) {
				path = "/"+path;
			}
		}

		String user = factory.getUser();
		if( user == null ) {
			user = "";
		}
		String pw = factory.getPassword();
		if( pw == null ) {
			pw = "";
		}

		int port = factory.getPort();		 
		String host = factory.getHost();
		String portStr = port == SftpFileSourceFactory.DEFAULT_PORT ? "":":"+port;

		//String url = FileSourceFactory.FILE_SOURCE_PROTOCOL+"://"+user+":"+pw+"@"+host+portStr+path+"?"+FileSourceFactory.QUERY_STRING_SOURCE_TYPE+"="+SftpFileSourceFactory.FACTORY_ID;
		String url = FileSourceFactory.FILE_SOURCE_PROTOCOL+"://"+user+"@"+host+portStr+path+"?"+FileSourceFactory.QUERY_STRING_SOURCE_TYPE+"="+SftpFileSourceFactory.FACTORY_ID;

		ret = new URL(url);

		return ret;
	}

	@Override
	public boolean isVersionSupported() throws IOException {
		return false;
	}

	@Override
	public long getVersion() throws IOException {
		return 0;
	}

	@Override
	public long getVersionDate() throws IOException {
		return 0;
	}

	@Override
	public void setVersionDate(long time) throws IOException {

	}

	@Override
	public void setVersion(long version, boolean saveChange) throws IOException {

	}

	@Override
	public long getMaxVersion() {
		return 0;
	}

	@Override
	public InputStream getInputStream(long skipTo) throws IOException {
		return new SftpInputStream(skipTo);
	}

	@Override
	public boolean equals(Object obj) {
		boolean ret = false;
		if (obj instanceof SftpFileSource) {
			SftpFileSource f = (SftpFileSource) obj;
			try {
				ret = f.getCanonicalPath().equals(getCanonicalPath());
			} catch (IOException e) {
			}
		}
		return ret ;
	}

	@Override
	public String toString() {
		try {
			return getCanonicalPath();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public  synchronized void dereferenceChilderen() {
		// release memory
		kids = null;

	}

	@Override
	public  synchronized void refresh() throws IOException {
		attr = null;
		kids = null;
		getAttr();

	}

	@Override
	public String getTitle() throws IOException {
		Session session = factory.getSession();
		return session.getUserName()+"@"+session.getHost()+":"+path;
	}

	@Override
	public FileSource[] listFiles(ProgressMonitor progress) throws IOException {
		throw new RuntimeException("list witH monitor not implimented"); 
	}

	@Override
	public FileSource getLinkedTo() throws IOException {
		if( isLink == null ) {
			if( exists()) {
				if( !getAttr().isLink()) {
					isLink = false;
				} else {
					isLink = true;
					try {
						String str = factory.readlink(path);
						linkedTo = factory.createFileSource(str);
					} catch (SftpException e) {
						throw new IOException(e);
					}	
				}
			}
		}
		return linkedTo;
	}

	@Override
	public boolean isHidden() {
		return getName().startsWith(".");
	}

	@Override
	public ISeekableInputStream getSeekableInputStream() throws IOException {
		throw new IOException("ISeekableInputStream not implemented");
	}


	@Override
	public UserPrincipal getOwner() throws IOException {
		if(owner == null ) {
			synchronized (this) {
				owner = new UserPrincipal() {
					@Override
					public String getName() {
						try {
							return ""+getAttr().getUId();
						} catch (IOException e) {
							return "UnKNown";
						}
					}
				};
			}
		}

		return owner;
	}

	@Override
	public GroupPrincipal getGroup() throws IOException {
		if( group == null ) {
			synchronized (this) {
				if( group == null ) {
					group = new GroupPrincipal() {
						@Override
						public String getName() {
							try {
								return ""+getAttr().getGId();
							} catch (IOException e) {
								return "UnKNown";
							}
						}
					};
				}
			}
		}
		return group;
	}

	@Override
	public void setExecutable(boolean b) throws IOException {
		try {

			int perm = getAttr().getPermissions() & 0b111111111;
			int p2 = -1;
			if( b ) {
				p2 = perm | 0b001000000;					
			} else {
				p2 = perm & 0b110111111;
			}

			if( p2 != perm) {								
				factory.getSftp_().chmod(p2, path);
				attr = null;
			}			
		} catch (SftpException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void setReadable(boolean readable) throws IOException {
		try {
			int perm = getAttr().getPermissions() & 0b111111111;
			int r2 = -1;
			if( readable ) {
				r2 = perm | 0b100000000;					
			} else {
				r2 = perm & 0b011111111;
			}			

			if( r2 != perm) {								
				factory.getSftp_().chmod(r2, path);
				attr = null;
			}		

		} catch (SftpException e) {
			throw new IOException(e);
		}

	}

	@Override
	public void setWritable(boolean b) throws IOException {
		try {
			int perm = getAttr().getPermissions() & 0b111111111;
			int r2 = -1;
			if( b ) {
				r2 = perm | 0b010000000;					
			} else {
				r2 = perm & 0b101111111;
			}

			if( r2 != perm) {								
				factory.getSftp_().chmod(r2, path);
				attr = null;
			}			
		} catch (SftpException e) {
			throw new IOException(e);
		}

	}

	@Override
	public void setExecutable(boolean executable, boolean ownerOnly) throws IOException {
		setExecutable(executable);
		if( ownerOnly ) {
			setGroupExecutable(!executable);
			setOtherExecutable(!executable);
		} 

	}

	@Override
	public void setReadable(boolean readable, boolean ownerOnly) throws IOException {
		setReadable(readable);
		if( ownerOnly ) {
			setGroupReadable(!readable);
			setOtherReadable(!readable);
		} 				
	}


	@Override
	public void setWritable(boolean writetable, boolean ownerOnly) throws IOException  {

		setWritable(writetable);
		if( ownerOnly ) {
			setGroupWritable(!writetable);
			setOtherWritable(!writetable);
		} 

	}

	@Override
	public void setGroupReadable(boolean b) throws IOException {
		try {

			int perm = getAttr().getPermissions() & 0b111111111;
			int r2 = -1;
			if( b ) {
				r2 = perm | 0b000100000;					
			} else {
				r2 = perm & 0b111011111;
			}

			if( r2 != perm) {								
				factory.getSftp_().chmod(r2, path);
				attr = null;
			}						

		} catch (SftpException e) {		
			throw new IOException(e);
		}
	}

	@Override
	public void setGroupWritable(boolean b) throws IOException {
		try {

			int perm = getAttr().getPermissions() & 0b111111111;

			int r2 = -1;
			if( b ) {
				r2 = perm | 0b000010000;					
			} else {
				r2 = perm & 0b111101111;
			}

			if( r2 != perm) {								
				factory.getSftp_().chmod(r2, path);
				attr = null;
			}				

		} catch (SftpException e) {		
			throw new IOException(e);
		}
	}

	@Override
	public void setGroupExecutable(boolean b) throws IOException {
		try {
			int perm = getAttr().getPermissions() & 0b111111111;

			int r2 = -1;
			if( b ) {
				r2 = perm | 0b000001000;					
			} else {
				r2 = perm & 0b111110111;
			}

			if( r2 != perm) {								
				factory.getSftp_().chmod(r2, path);
				attr = null;
			}			

		} catch (SftpException  e) {
			throw new IOException(e);
		}
	}

	@Override
	public void setOtherReadable(boolean b) throws IOException {
		try {
			int perm = getAttr().getPermissions() & 0b111111111;

			int r2 = -1;
			if( b ) {
				r2 = perm | 0b000000100;					
			} else {
				r2 = perm & 0b111111011;
			}

			if( r2 != perm) {								
				factory.getSftp_().chmod(r2, path);
				attr = null;
			}			

		} catch (SftpException e) {		
			throw new IOException(e);
		}
	}

	@Override
	public void setOtherWritable(boolean b) throws IOException {
		try {

			int perm = getAttr().getPermissions() & 0b111111111;

			int r2 = -1;
			if( b ) {
				r2 = perm | 0b000000010;					
			} else {
				r2 = perm & 0b111111101;
			}

			if( r2 != perm) {								
				factory.getSftp_().chmod(r2, path);
				attr = null;
			}			

		} catch (SftpException e) {		
			throw new IOException(e);
		}
	}

	@Override
	public void setOtherExecutable(boolean b) throws IOException {
		try {

			int perm = getAttr().getPermissions() & 0b111111111;

			int r2 = -1;
			if( b ) {
				r2 = perm | 0b000000001;					
			} else {
				r2 = perm & 0b111111110;
			}

			if( r2 != perm) {								
				factory.getSftp_().chmod(r2, path);
				attr = null;
			}			

		} catch (SftpException e) {		
			throw new IOException(e);
		}
	}

	@Override
	public void setOwnerReadable(boolean b) throws IOException {
		setReadable(b);
	}

	@Override
	public void setOwnerWritable(boolean b) throws IOException {
		setWritable(b);
	}

	@Override
	public void setOwnerExecutable(boolean b) throws IOException {
		setExecutable(b);
	}

	@Override
	public long lastAccessTime() throws IOException {
		return getAttr().getATime()*1000;
	}

	@Override
	public long creationTime() throws IOException {
		// not supported
		return 0;
	}

}
