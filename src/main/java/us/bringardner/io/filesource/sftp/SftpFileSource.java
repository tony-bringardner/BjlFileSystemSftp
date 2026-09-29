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
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;

import javax.swing.ProgressMonitor;

import us.bringardner.core.BaseObject;
import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceFactory;
import us.bringardner.io.filesource.FileSourceFilter;
import us.bringardner.io.filesource.FileSourceGroup;
import us.bringardner.io.filesource.FileSourceRandomAccessStream;
import us.bringardner.io.filesource.IRandomAccessStream;
import us.bringardner.io.filesource.FileSourceUser;
import us.bringardner.io.filesource.ISeekableInputStream;
import us.bringardner.io.filesource.fileproxy.FileProxy;
import us.bringardner.io.filesource.sftp.client.SftpAttributes;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpEntry;

public class SftpFileSource extends BaseObject implements FileSource {


	private static final long serialVersionUID = 1L;


	private class SftpOutputStream extends OutputStream {

		/** Each stream has its own SFTP channel, so streams can be used from any thread. */
		private final SftpChannel mySftp;
		private final OutputStream out;

		SftpOutputStream (boolean append) throws IOException {
			attr = null;
			exists = null;

			mySftp = factory.getConnection().openSftp();
			try {
				this.out = mySftp.write(path, append);
			} catch (IOException | RuntimeException e) {
				// don't leak the channel when the open fails
				mySftp.close();
				throw e;
			}

		}
		@Override
		public void write(int b) throws IOException {
			out.write(b);
		}

		private boolean closed = false;

		/**
		 * Closing sends the last buffered data and checks the server's replies,
		 * so an error here (disk full, quota, permission) means the file is
		 * incomplete. It used to be swallowed; now it's thrown.
		 */
		@Override
		public void close() throws IOException {
			if( closed ) {
				return;
			}
			closed = true;
			try {
				out.close();
			} finally {
				mySftp.close();
				clearAttr();
				clearParentKids();
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

		/** Each stream has its own SFTP channel, so streams can be used from any thread. */
		private final SftpChannel mySftp;
		private final InputStream in;

		SftpInputStream() throws IOException {
			this(0);
		}

		public SftpInputStream(long skipTo) throws IOException {
			attr = null;
			exists = null;

			mySftp = factory.getConnection().openSftp();
			try {
				in = mySftp.read(path, skipTo);
			} catch (IOException | RuntimeException e) {
				// don't leak the channel when the open fails
				mySftp.close();
				throw e;
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
			} finally {
				mySftp.close();
			}
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
	private SftpAttributes attr;
	private Boolean exists;
	private SftpFileSource[] kids;
	private String path;
	private SftpFileSourceFactory factory;
	private Boolean isLink;


	private SftpFileSource parent;
	private FileSource linkedTo;
	private FileSourceUser owner;
	private FileSourceGroup group;

	/**
	 * Construct a new SftpFileSource from 'factory' 
	 * 
	 * @param factory
	 * @param path
	 */
	SftpFileSource(SftpFileSourceFactory factory, String path) {
		this.factory = factory;
		this.path = path;
		if( path.equals("/")) {
			name = "/";
		} else {
			name = path.substring(path.lastIndexOf('/')+1);
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
		String parentPath = parent.getCanonicalPath();
		this.path = parentPath.endsWith("/") ? parentPath+name : parentPath+"/"+name;
		this.parent.addChild(this);
	}

	/**
	 * Construct a SftpFileSource as a child of parent .
	 * 
	 * @param factory
	 * @param parent
	 * @param entry : The list entry for this file.
	 */
	private SftpFileSource(SftpFileSourceFactory factory, SftpFileSource parent, SftpEntry entry) {
		this.factory = factory;

		this.parent = parent;
		this.name = entry.getFilename();
		if( parent.path.equals("/")) {
			this.path = "/"+this.name;
		} else {
			this.path = parent.path+"/"+this.name;
		}
		factory.rememberNames(entry);

		SftpAttributes a = entry.getAttrs();
		if( a.isLink()) {
			// A listing describes the link itself. Leave the attributes to be
			// read with stat(), which follows the link like java.io.File does.
			this.isLink = true;
		} else {
			this.attr = a;
			this.isLink = false;
			//  We have a list entry so this file MUST exists.
			this.exists = (true);
		}
	}

	private synchronized SftpFileSource[] getKids(ProgressMonitor monitor) throws IOException {
		if( monitor != null) monitor.setProgress(0);
		if( kids == null ) {
			List<FileSource> list = new ArrayList<FileSource>();
			if( !isFile() ) {
				// if it's not a file it may be a directory or a new / non existing entry
				try {
					List<SftpEntry> ls = factory.ls(path);
					int cnt = 0;
					for (SftpEntry e : ls) {
						cnt++;
						
						//System.out.println("name ="+e.getFilename());
						if( ! (e.getFilename().equals(".") || e.getFilename().equals(".."))) {
							list.add(new SftpFileSource(factory, this,e));
						}
						if( monitor != null) monitor.setProgress((int)((long)cnt*monitor.getMaximum()/ls.size()));
					}
				} catch (IOException e) {
					if( isNoSuchFile(e)) {
						exists = (false);
					} else {
						throw e;
					}
				}
				kids = list.toArray(new SftpFileSource[list.size()]);
			}
		}
		
		if( monitor != null) monitor.setProgress(monitor.getMaximum());
		return kids;
	}

	/** A file was created or removed in the parent directory, so its cached listing is stale. */
	private void clearParentKids() {
		if( parent != null ) {
			parent.dereferenceChilderen();
		}
	}

	private synchronized void clearOwner() {
		attr = null;
		owner = null;
		group = null;
	}

	/** Forget cached attributes, so the next call re-reads them from the server. */
	synchronized void clearAttr()  {
		attr = null;
		exists = null;
	}

	/**
	 * True when the server says the file doesn't exist. Uses the SFTP status
	 * code, not the message text, which differs between servers
	 * ("No such file", "No such file or directory", "File not found", ...).
	 */
	static boolean isNoSuchFile(IOException e) {
		return e instanceof NoSuchFileException
				|| (e.getMessage() != null && e.getMessage().endsWith("not a valid file path"));
	}

	/**
	 * The file's attributes, following symbolic links like java.io.File does:
	 * a link to a directory is a directory, and a link whose target is missing
	 * doesn't exist. Returns null if the file doesn't exist.
	 */
	private synchronized SftpAttributes getAttr() throws IOException {
		if( attr == null ) {
			try {
				attr = factory.stat(path);
				exists = (true);
			} catch (IOException e) {
				if( isNoSuchFile(e)) {
					exists = (false);
				} else {
					throw e;
				}
			}
		}
		return attr;
	}

	/** Attributes of the path itself, without following a link; null if there's nothing there. */
	private SftpAttributes getLinkAttr() throws IOException {
		try {
			return factory.lstat(path);
		} catch (IOException e) {
			if( isNoSuchFile(e)) {
				return null;
			}
			throw e;
		}
	}

	/**
	 * Permission check against the remote user from whoAmI(). Root can read
	 * and write anything, and execute anything with an execute bit set.
	 */
	private boolean hasPermission(int ownerBit) throws IOException {
		SftpAttributes a = getAttr();
		if( a == null ) {
			return false;
		}
		int perm = a.getPermissions();
		FileSourceUser me = factory.whoAmI();
		if( me.getId() == 0 ) {
			return ownerBit != 0100 || (perm & 0111) != 0;
		}
		if( me.getId() == a.getUId()) {
			return (perm & ownerBit) != 0;
		}
		if( me.hasGroup(a.getGId())) {
			return (perm & (ownerBit >> 3)) != 0;
		}
		return (perm & (ownerBit >> 6)) != 0;
	}
	
	/**
	 * Tests whether the application can execute the file denoted by this abstract pathname. 
	 * This is only here for comparability with java.io.File.  
	 * 
	 * @return
	 * @throws IOException 
	 * 
	 */
	@Override
	public boolean canExecute() throws IOException {
		// Connection and server errors are thrown; they used to be swallowed as "false".
		return hasPermission(0100);
	}


	/**
	 * Tests whether the application can read the 
	 * file denoted by this abstract pathname.
	 * This is only here for comparability with java.io.File.  
	 * 
	 * @return true if and only if the file system actually contains a file denoted by this abstract pathname 
	 * 	and the application is allowed to read the file; false otherwise.  
	 * @throws IOException 
	 * 
	 */
	@Override
	public boolean canRead() throws IOException {
		// Connection and server errors are thrown; they used to be swallowed as "false".
		return hasPermission(0400);
	}

	/**
	 * Tests whether the application can modify the file denoted by this abstract pathname. 
	 * This is only here for comparability with java.io.File.  
	 * 
	 * @return
	 * @throws IOException 
	 * 
	 */
	@Override
	public boolean canWrite() throws IOException {
		// Connection and server errors are thrown; they used to be swallowed as "false".
		return hasPermission(0200);
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
	public boolean canOwnerRead() throws IOException {
		return (getAttr().getPermissionsString().charAt(1) == 'r');//-rw-r--r--;
	}

	@Override
	public boolean canOwnerWrite() throws IOException {
		return (getAttr().getPermissionsString().charAt(2) == 'w');//-rw-r--r--;
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
		if( !exists()) {
			try(OutputStream out =  getOutputStream()) {

			}
		}
		return exists();
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
		SftpFileSource ret = new SftpFileSource(factory,this,parts[0]);
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

	@Override
	public synchronized boolean delete() throws IOException {
		boolean ret = false;
		SftpAttributes self = getLinkAttr();   // the path itself, so a link is removed, not its target
		if( self == null ) {
			return false;   // like java.io.File: nothing to delete
		}
		if( self.isDir() ) {
			factory.sftp(c -> { c.rmdir(path); return null; });
		} else {
			factory.sftp(c -> { c.remove(path); return null; });
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
		SftpAttributes a = getAttr();

		return a == null ? false :  a.isDir();
	}

	@Override
	public synchronized  boolean isFile() throws IOException {
		SftpAttributes a = getAttr();

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
		return getKids(null);
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
		factory.sftp(c -> { c.mkdir(path); return null; });
		attr =  null;
		exists = null;
		ret = exists();

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

			if (dest instanceof SftpFileSource) {
				SftpFileSource fs = (SftpFileSource) dest;


				if( !(myName.equals("/") || yourName.equals("/") || myName.equals(yourName))) {
					factory.sftp(c -> { c.rename(myName, yourName); return null; });
					ret = true;
					fs.attr =attr = null;
					fs.exists = exists = null;
					fs.kids = kids = null;
					((SftpFileSource)getParentFile()).kids = null;
				}	
			}
		}
		return ret;
	}

	@Override
	public synchronized  boolean setLastModifiedTime(long time) throws IOException {
		boolean ret = false;
		int time2 = (int)(time/1000);
		factory.sftp(c -> { c.setModifiedTime(path, time2); return null; });
		attr = null;
		ret = getAttr().getMTime()==time2;
		
		return ret;
	}

	@Override
	public boolean setReadOnly() throws IOException {
		// not supported
		return false;
	}

	@Override
	public InputStream getInputStream() throws FileNotFoundException,IOException {
		try {
			return new SftpInputStream();
		} catch (IOException e) {
			throw openError(path, e);
		}
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return getOutputStream(false);
	}

	@Override
	public OutputStream getOutputStream(boolean append)	throws IOException {
		try {
			return new SftpOutputStream(append);
		} catch (IOException e) {
			throw openError(path, e);
		}
	}

	/**
	 * Opening a file that is missing or not permitted throws
	 * FileNotFoundException, as java.io's streams and RandomAccessFile do
	 * (and as the FileSource methods declare). Other errors pass through.
	 */
	static IOException openError(String path, IOException e) {
		if( e instanceof FileNotFoundException ) {
			return e;
		}
		if( e instanceof NoSuchFileException ) {
			return (IOException) new FileNotFoundException(path+" (No such file or directory)").initCause(e);
		}
		if( e instanceof AccessDeniedException ) {
			return (IOException) new FileNotFoundException(path+" (Permission denied)").initCause(e);
		}
		return e;
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
	public boolean setVersionDate(long time) throws IOException {
		return false;
	}

	@Override
	public boolean setVersion(long version, boolean saveChange) throws IOException {
		return false;
	}

	@Override
	public long getMaxVersion() {
		return 0;
	}

	@Override
	public InputStream getInputStream(long skipTo) throws IOException {
		try {
			return new SftpInputStream(skipTo);
		} catch (IOException e) {
			throw openError(path, e);
		}
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
	public int hashCode() {
		return path.hashCode();
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
		exists = null;
		kids = null;
		isLink = null;
		linkedTo = null;
		owner = null;
		group = null;
		getAttr();

	}

	@Override
	public String getTitle() throws IOException {
		return factory.getUser()+"@"+factory.getHost()+":"+path;
	}

	@Override
	public FileSource[] listFiles(ProgressMonitor progress) throws IOException {
		return getKids(progress);
	}

	@Override
	public synchronized FileSource getLinkedTo() throws IOException {
		if( isLink == null || (isLink && linkedTo == null)) {
			SftpAttributes self = getLinkAttr();
			isLink = self != null && self.isLink();
			if( isLink ) {
				String target = factory.readlink(path);
				if( !target.startsWith("/")) {
					// a relative target is relative to the link's own directory
					String dir = getParent();
					target = (dir == null ? "" : dir) + "/" + target;
				}
				linkedTo = factory.createFileSource(target);
			}
		}
		return isLink ? linkedTo : null;
	}

	@Override
	public boolean isHidden() {
		return getName().startsWith(".");
	}

	@Override
	/** A read-only stream that can seek; see SftpSeekableInputStream. */
	public ISeekableInputStream getSeekableInputStream() throws IOException {
		return new SftpSeekableInputStream(this);
	}

	/**
	 * Random access in RandomAccessFile's modes: "r" opens read-only; "rw"
	 * (and "rws", "rwd") opens for reading and writing, creating the file if
	 * it doesn't exist.
	 *
	 * @throws FileNotFoundException if the file is a directory, is missing in
	 *         "r" mode, or can't be opened with the requested access
	 */
	@Override
	public IRandomAccessStream getRandomAccessStream(String mode) throws IOException {
		SftpRandomAccessIoController io = new SftpRandomAccessIoController(this, mode);
		try {
			return new FileSourceRandomAccessStream(io, mode);
		} catch (IOException | RuntimeException e) {
			try {
				io.close();
			} catch (Exception e2) {
				e.addSuppressed(e2);
			}
			throw e;
		}
	}

	/**
	 * The file's owner and group. SFTP gives only numeric ids, so the names
	 * come from the ls-style long listing; the factory remembers them from
	 * every listing, so usually this costs nothing. It used to list the file's
	 * own path, which for a directory listed its children and took the first
	 * entry's owner.
	 */
	@Override
	public synchronized FileSourceUser getOwner() throws IOException {
		if( owner == null ) {
			SftpAttributes a = getAttr();
			if( a == null ) {
				return new FileSourceUser();
			}
			String userName = factory.userName(a.getUId());
			String groupName = factory.groupName(a.getGId());
			if( userName == null || groupName == null ) {
				// look the names up in the directory that lists this file
				String dir = getParent();
				try {
					factory.ls(dir == null ? path : dir);   // remembers every entry's names
				} catch (IOException e) {
					if( !isNoSuchFile(e) && !(e instanceof AccessDeniedException)) {
						throw e;
					}
				}
				userName = factory.userName(a.getUId());
				groupName = factory.groupName(a.getGId());
			}
			owner = new FileSourceUser(a.getUId(), userName == null ? ""+a.getUId() : userName,
					a.getGId(), groupName == null ? ""+a.getGId() : groupName);
		}
		return owner;
	}

	@Override
	public synchronized GroupPrincipal getGroup() throws IOException {
		if( group == null ) {
			group = getOwner().getGroup();
		}
		return group;
	}

	/**
	 * Sets and clears permission bits with a single chmod. Keeps the
	 * setuid/setgid/sticky bits (the old code masked them off). Returns false
	 * if the file doesn't exist or the server refuses.
	 */
	private boolean changeMode(int set, int clear) throws IOException {
		SftpAttributes a = getAttr();
		if( a == null ) {
			return false;
		}
		int perm = a.getPermissions() & 07777;
		int mode = (perm | set) & ~clear;
		if( mode != perm ) {
			try {
				factory.sftp(c -> { c.chmod(path, mode); return null; });
			} catch (AccessDeniedException | NoSuchFileException e) {
				return false;
			} finally {
				clearAttr();
			}
		}
		return true;
	}

	private boolean changeMode(boolean on, int bits) throws IOException {
		return on ? changeMode(bits, 0) : changeMode(0, bits);
	}

	@Override
	public boolean setExecutable(boolean b) throws IOException {
		return changeMode(b, 0100);
	}

	@Override
	public boolean setReadable(boolean b) throws IOException {
		return changeMode(b, 0400);
	}

	@Override
	public boolean setWritable(boolean b) throws IOException {
		return changeMode(b, 0200);
	}

	@Override
	public boolean setExecutable(boolean b, boolean ownerOnly) throws IOException {
		return changeMode(b, ownerOnly ? 0100 : 0111);
	}

	@Override
	public boolean setReadable(boolean b, boolean ownerOnly) throws IOException {
		return changeMode(b, ownerOnly ? 0400 : 0444);
	}

	@Override
	public boolean setWritable(boolean b, boolean ownerOnly) throws IOException  {
		return changeMode(b, ownerOnly ? 0200 : 0222);
	}

	@Override
	public boolean setGroupReadable(boolean b) throws IOException {
		return changeMode(b, 0040);
	}

	@Override
	public boolean setGroupWritable(boolean b) throws IOException {
		return changeMode(b, 0020);
	}

	@Override
	public boolean setGroupExecutable(boolean b) throws IOException {
		return changeMode(b, 0010);
	}

	@Override
	public boolean setOtherReadable(boolean b) throws IOException {
		return changeMode(b, 0004);
	}

	@Override
	public boolean setOtherWritable(boolean b) throws IOException {
		return changeMode(b, 0002);
	}

	@Override
	public boolean setOtherExecutable(boolean b) throws IOException {
		return changeMode(b, 0001);
	}

	@Override
	public boolean setOwnerReadable(boolean b) throws IOException {
		return setReadable(b);
	}

	@Override
	public boolean setOwnerWritable(boolean b) throws IOException {
		return setWritable(b);
	}

	@Override
	public boolean setOwnerExecutable(boolean b) throws IOException {
		return setExecutable(b);
	}

	@Override
	public long lastAccessTime() throws IOException {
		return getAttr().getATime()*1000L;
	}

	@Override
	public long creationTime() throws IOException {
		// not supported
		return 0;
	}

	@Override
	public boolean setLastAccessTime(long time) throws IOException {
		int time2 = (int)(time/1000);
		factory.sftp(c -> { c.setAccessTime(path, time2); return null; });
		attr = null;
		return getAttr().getATime() == time2;
	}

	@Override
	public boolean setCreateTime(long time) throws IOException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean setGroup(GroupPrincipal group) throws IOException {
		if (group instanceof FileSourceGroup) {
			int gid =  ((FileSourceGroup) group).getId();
			factory.sftp(c -> { c.chgrp(path, gid); return null; });
			clearOwner();
			return true;
		}
		return false;
	}

	@Override
	public boolean setOwner(UserPrincipal owner) throws IOException {
		if (owner instanceof FileSourceUser) {
			int uid = ((FileSourceUser) owner).getId();
			factory.sftp(c -> { c.chown(path, uid); return null; });
			clearOwner();
			return true;
		}
		return false;
	}

}
