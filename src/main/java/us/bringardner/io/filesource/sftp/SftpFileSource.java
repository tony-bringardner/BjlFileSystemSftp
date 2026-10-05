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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public class SftpFileSource extends BaseObject implements FileSource {


	private static final long serialVersionUID = 1L;


	private class SftpOutputStream extends OutputStream {

		/** Each stream has its own SFTP channel, so streams can be used from any thread. */
		private final SftpChannel mySftp;
		private final OutputStream out;
		/**
		 * With the factory's safeOverwrite: the temporary file the data goes to,
		 * and the file close() puts it in place of. Otherwise both are null.
		 */
		private final String tempPath;
		private final String targetPath;

		SftpOutputStream (boolean append) throws IOException {
			attr = null;
			exists = null;

			mySftp = factory.openSftp();
			String temp = null;
			String target = null;
			try {
				if( !append && factory.isSafeOverwrite()) {
					target = replaceTarget();
					temp = tempPathFor(target);
					this.out = mySftp.write(temp, false);
				} else {
					this.out = mySftp.write(path, append);
				}
			} catch (IOException | RuntimeException e) {
				// don't leak the channel when the open fails
				mySftp.close();
				throw e;
			}
			tempPath = temp;
			targetPath = target;
		}

		/** The file to replace: this one, or the one a symbolic link here points to. */
		private String replaceTarget() throws IOException {
			try {
				if( mySftp.lstat(path).isLink()) {
					return getCanonicalPath();   // replace what it points to, and keep the link
				}
			} catch (NoSuchFileException e) {
				// a new file
			}
			return path;
		}

		/** Puts the temporary file in place of the target, keeping the target's permission bits. */
		private void putInPlace() throws IOException {
			int mode = -1;
			try {
				mode = mySftp.stat(targetPath).getPermissions() & 07777;
			} catch (NoSuchFileException e) {
				// no old file: the new one keeps the server's default permissions
			}
			if( mode >= 0 ) {
				mySftp.chmod(tempPath, mode);
			}
			mySftp.replace(tempPath, targetPath);
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
				if( tempPath != null ) {
					putInPlace();
				}
			} catch (IOException | RuntimeException e) {
				if( tempPath != null ) {
					// the old file is untouched; don't leave the half-written copy
					try {
						factory.sftp(c -> { c.remove(tempPath); return null; });
					} catch (NoSuchFileException e2) {
						// already gone
					} catch (IOException | RuntimeException e2) {
						e.addSuppressed(e2);
					}
				}
				throw e;
			} finally {
				mySftp.close();
				clearAttr();
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
			// Reading changes nothing worth asking the server about again, so the
			// cached attributes are kept (clearing them cost a stat after every read).
			mySftp = factory.openSftp();
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
	/*
	 * Cached answers from the server. They are trusted for the factory's
	 * attribute cache time (getAttributeCacheTtl); after that the next call
	 * asks the server again. Directory listings are not cached at all.
	 */
	private SftpAttributes attr;
	private Boolean exists;
	/** System.nanoTime() when the cached answers above and below were filled. */
	private long cachedAt;
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
		// the parent's own path: resolving links here would cost a server round trip per child
		String parentPath = parent.path;
		this.path = parentPath.endsWith("/") ? parentPath+name : parentPath+"/"+name;
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
		this.cachedAt = System.nanoTime();
	}

	/**
	 * Lists the directory. Every call asks the server, like java.io.File, so
	 * a listing is never stale; the listing carries every child's attributes,
	 * so isDirectory()/length() on the results cost nothing more. (Listings
	 * used to be cached per object, so a change made through another object,
	 * or by another program, was never seen.)
	 *
	 * @return the children, or null if this is a file or doesn't exist
	 */
	private SftpFileSource[] getKids(ProgressMonitor monitor) throws IOException {
		if( monitor != null) monitor.setProgress(0);
		SftpFileSource[] ret = null;
		if( !isFile() ) {
			// if it's not a file it may be a directory or a new / non existing entry
			List<FileSource> list = new ArrayList<FileSource>();
			try {
				List<SftpEntry> ls = factory.ls(path);
				int cnt = 0;
				for (SftpEntry e : ls) {
					cnt++;
					if( ! (e.getFilename().equals(".") || e.getFilename().equals(".."))) {
						list.add(new SftpFileSource(factory, this,e));
					}
					if( monitor != null) monitor.setProgress((int)((long)cnt*monitor.getMaximum()/ls.size()));
				}
			} catch (IOException e) {
				if( isNoSuchFile(e)) {
					synchronized (this) {
						attr = null;
						exists = null;
						isLink = null;
						owner = null;
						group = null;
						linkedTo = null;
						startClockIfEmpty();
						exists = false;
					}
				} else {
					throw e;
				}
			}
			ret = list.toArray(new SftpFileSource[list.size()]);
		}

		if( monitor != null) monitor.setProgress(monitor.getMaximum());
		return ret;
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
	 * Drops the cached answers once they are older than the factory's
	 * attribute cache time: 0 means never trust them, a negative time means
	 * keep them until refresh().
	 */
	/**
	 * Call before caching a new answer. Starts the clock if nothing is cached
	 * yet, so cachedAt is always the time of the oldest cached answer and
	 * refilling one value never extends the life of the others.
	 */
	private void startClockIfEmpty() {
		if( attr == null && exists == null && isLink == null && owner == null && group == null ) {
			cachedAt = System.nanoTime();
		}
	}

	private synchronized void expireIfStale() {
		long ttl = factory.getAttributeCacheTtl();
		if( ttl < 0 ) {
			return;
		}
		if( ttl == 0 || System.nanoTime() - cachedAt > ttl * 1_000_000L ) {
			attr = null;
			exists = null;
			isLink = null;
			linkedTo = null;
			owner = null;
			group = null;
		}
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
		expireIfStale();
		if( attr == null ) {
			SftpAttributes a = null;
			Boolean e1;
			try {
				a = factory.stat(path);
				e1 = true;
			} catch (IOException e) {
				if( isNoSuchFile(e)) {
					e1 = false;
				} else {
					throw e;
				}
			}
			startClockIfEmpty();
			attr = a;
			exists = e1;
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

	/**
	 * Orders by absolute path, consistent with equals(). It used to compare
	 * with the other object's toString() and, on an error, print a stack
	 * trace and return -1, which breaks sorting (a < b and b < a).
	 *
	 * @throws NullPointerException if o is null, as Comparable requires
	 */
	@Override
	public int compareTo(Object o) {
		String other = o instanceof FileSource ? ((FileSource) o).getAbsolutePath() : o.toString();
		return path.compareTo(other);
	}

	@Override
	public long getCreateDate() throws IOException {
		return 0;
	}

	@Override
	public String getContentType() {
		return FileProxy.getContentType(getName());
	}

	/**
	 * One character of the "-rwxr-xr-x" permission string, or ' ' if the file
	 * doesn't exist (so every permission check answers false).
	 */
	private char permissionChar(int idx) throws IOException {
		SftpAttributes a = getAttr();
		return a == null ? ' ' : a.getPermissionsString().charAt(idx);
	}

	@Override
	public boolean canOwnerRead() throws IOException {
		return permissionChar(1) == 'r';
	}

	@Override
	public boolean canOwnerWrite() throws IOException {
		return permissionChar(2) == 'w';
	}

	@Override
	public boolean canOwnerExecute() throws IOException {
		return permissionChar(3) == 'x';
	}

	@Override
	public boolean canGroupRead() throws IOException {
		return permissionChar(4) == 'r';
	}

	@Override
	public boolean canGroupWrite() throws IOException {
		return permissionChar(5) == 'w';
	}

	@Override
	public boolean canGroupExecute() throws IOException {
		return permissionChar(6) == 'x';
	}

	@Override
	public boolean canOtherRead() throws IOException {
		return permissionChar(7) == 'r';
	}

	@Override
	public boolean canOtherWrite() throws IOException {
		return permissionChar(8) == 'w';
	}

	@Override
	public boolean canOtherExecute() throws IOException {
		return permissionChar(9) == 'x';
	}

	/**
	 * Creates an empty file if, and only if, nothing is at this path, like
	 * java.io.File: true if it was created, false if something was already
	 * there. It used to check the cached exists() and then open with truncate,
	 * so a file created elsewhere since the cache was filled was emptied.
	 */
	@Override
	public synchronized boolean createNewFile() throws IOException {
		boolean created = factory.sftp(c -> c.createNew(path));
		clearAttr();
		return created;
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
		clearAttr();
		ret = true;
		return ret;
	}

	@Override
	public synchronized  boolean exists() throws IOException {
		expireIfStale();
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

	/**
	 * The path with "." and ".." resolved and symbolic links followed (BJL-12).
	 * <p>
	 * Each element is checked on the server (lstat, and readlink for a link), because
	 * the SFTP protocol doesn't require REALPATH to follow links. Below the first element
	 * that doesn't exist the rest is resolved by name, until a ".." climbs back into
	 * existing directories. Fails (IOException) rather than guess if an element can't be
	 * checked, e.g. permission denied; isChildOfMine then answers false.
	 */
	@Override
	public String getCanonicalPath() throws IOException {
		String abs = path;
		if( !abs.startsWith("/")) {
			abs = factory.getCurrentDirectory().getAbsolutePath()+"/"+abs;
		}
		return canonicalize(factory, abs);
	}

	/**
	 * A name for a temporary file beside 'target': ".name.random.tmp" in the
	 * same directory, so renaming it over the target stays on one file system.
	 */
	static String tempPathFor(String target) {
		int slash = target.lastIndexOf('/');
		String dir = slash < 0 ? "" : target.substring(0, slash + 1);
		String name = target.substring(slash + 1);
		return dir+"."+name+"."+Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE)+".tmp";
	}

	/** As in POSIX (SYMLOOP_MAX / Linux ELOOP). */
	static final int MAX_LINKS = 40;

	static String canonicalize(SftpFileSourceFactory factory, String absolutePath) throws IOException {
		Deque<String> todo = new ArrayDeque<>(Arrays.asList(absolutePath.split("/")));
		List<String> done = new ArrayList<>();
		// index in 'done' of the first element that doesn't exist, or -1
		int missingAt = -1;
		int links = 0;
		while( !todo.isEmpty()) {
			String part = todo.pollFirst();
			if( part.isEmpty() || part.equals(".")) {
				continue;
			}
			if( part.equals("..")) {
				if( !done.isEmpty()) {
					done.remove(done.size()-1);
				}
				if( missingAt >= done.size()) {
					// back in directories that exist: check links again
					missingAt = -1;
				}
				continue;
			}
			done.add(part);
			if( missingAt >= 0 ) {
				continue;
			}
			String current = "/"+String.join("/", done);
			SftpAttributes a;
			try {
				a = factory.lstat(current);
			} catch (IOException e) {
				if( isNoSuchFile(e)) {
					missingAt = done.size()-1;
					continue;
				}
				throw e;
			}
			if( a != null && a.isLink()) {
				if( ++links > MAX_LINKS ) {
					throw new IOException("Too many levels of symbolic links: "+absolutePath);
				}
				String target = factory.readlink(current);
				// a relative target is relative to the link's directory
				done.remove(done.size()-1);
				if( target.startsWith("/")) {
					done.clear();
				}
				List<String> t = Arrays.asList(target.split("/"));
				for(int idx = t.size()-1; idx >= 0; idx--) {
					todo.addFirst(t.get(idx));
				}
			}
		}

		return "/"+String.join("/", done);
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

	/**
	 * Makes this directory and any missing parents. True if it's a directory
	 * at the end, including when it already was; false if a file is in the
	 * way (it used to say true then). If another program makes one of the
	 * directories meanwhile, that's success: mkdir used to fail on it with an
	 * IOException, which a cached exists() made likely for up to the cache time.
	 */
	@Override
	public  synchronized boolean mkdirs() throws IOException {
		if( exists()) {
			return isDirectory();
		}
		FileSource p = getParentFile();
		if( p != null && !p.mkdirs()) {
			return false;
		}
		try {
			return mkdir();
		} catch (IOException e) {
			clearAttr();
			if( isDirectory()) {
				return true;   // made by someone else since exists() was answered
			}
			throw e;
		}
	}

	@Override
	public synchronized  boolean renameTo(FileSource dest) throws IOException {
		// the paths themselves: renaming a link renames the link, not its target
		String myName = path;
		String yourName = dest.getAbsolutePath();

		// Only within one server account: the rename runs on this server, so a
		// destination on another server used to rename to its path here.
		if( !(dest instanceof SftpFileSource) || !((SftpFileSource) dest).factory.isSameFileSystem(factory)) {
			return false;
		}
		SftpFileSource fs = (SftpFileSource) dest;
		boolean ret = false;
		if( exists() && !dest.exists()) {
			if( !(myName.equals("/") || yourName.equals("/") || myName.equals(yourName))) {
				factory.sftp(c -> { c.rename(myName, yourName); return null; });
				ret = true;
				fs.clearAttr();
				clearAttr();
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
		SftpAttributes a = getAttr();   // null if it was deleted meanwhile
		ret = a != null && a.getMTime()==time2;
		
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

		path = getAbsolutePath();
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
			// the same path on the same server account, consistent with compareTo()
			ret = f.path.equals(path) && f.factory.isSameFileSystem(factory);
		}
		return ret ;
	}

	@Override
	public int hashCode() {
		return path.hashCode();
	}

	@Override
	public String toString() {
		return path;
	}

	@Override
	public void dereferenceChilderen() {
		// nothing to release: directory listings aren't cached
	}

	@Override
	public  synchronized void refresh() throws IOException {
		attr = null;
		exists = null;
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
		expireIfStale();
		if( isLink == null || (isLink && linkedTo == null)) {
			SftpAttributes self = getLinkAttr();
			startClockIfEmpty();
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
		expireIfStale();
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
				if( userName == null || groupName == null ) {
					// so the next file with this owner doesn't list the directory again
					factory.rememberUnknownNames(a.getUId(), a.getGId());
				}
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
	 * <p>
	 * Starts from the server's current mode, never the cached one: working
	 * from a cached mode would silently undo any change made elsewhere since
	 * it was cached (a lost update).
	 */
	private boolean changeMode(int set, int clear) throws IOException {
		clearAttr();
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
		SftpAttributes a = getAttr();
		return a == null ? 0 : a.getATime()*1000L;
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
		SftpAttributes a = getAttr();   // null if it was deleted meanwhile
		return a != null && a.getATime() == time2;
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
