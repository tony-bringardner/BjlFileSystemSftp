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
 * ~version~V000.01.06-V000.01.05-V000.01.04-V000.01.03-V000.01.02-V000.01.01-V000.01.00-V000.00.01-V000.00.00-
 */
package us.bringardner.io.filesource.sftp;

import java.awt.Component;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceFactory;
import us.bringardner.io.filesource.FileSourceUser;
import us.bringardner.io.filesource.sftp.client.SftpAttributes;
import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SftpEntry;
import us.bringardner.io.filesource.sftp.client.SshConnection;
import us.bringardner.io.filesource.sftp.client.SshProvider;
import us.bringardner.io.filesource.sftp.client.SshProviders;
import us.bringardner.io.filesource.sftp.client.SshSettings;

/**
 * FileSource factory for SFTP. The SSH library is chosen at run time: set
 * the "implementation" connection property (or the system property
 * bjl.sftp.implementation) to "jsch" (the default) or "mina".
 */
public class SftpFileSourceFactory extends FileSourceFactory {


	/**
	 *
	 */
	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = "sftp";

	public static final String PROP_SESSION_KEY = "sessionKey";
	public static final String PROP_HOST = "host";
	public static final String PROP_PORT = "port";
	public static final String PROP_USER = "user";
	public static final String PROP_PRIVATE_KEY_FILE_NAME = "identityFile";
	public static final String PROP_PRIVATE_KEY = "privateKey";
	public static final String PROP_PASSWORD = "password";
	/** Path of a known_hosts file used to check the server's host key. */
	public static final String PROP_KNOWN_HOSTS = "knownHosts";
	/** "no" (default) accepts any host key; "yes" requires it to be in the known_hosts file. */
	public static final String PROP_STRICT_HOST_KEY_CHECKING = "strictHostKeyChecking";
	public static final String PROP_CONNECT_TIMEOUT = "connectTimeout";
	public static final String PROP_SERVER_ALIVE_INTERVAL = "serverAliveInterval";
	/** SSH library: "jsch" or "mina"; empty means the system property bjl.sftp.implementation, else jsch. */
	public static final String PROP_IMPLEMENTATION = "implementation";
	public static final int DEFAULT_PORT = 22;
	public static final int DEFAULT_CONNECT_TIMEOUT = 30_000;
	public static final int DEFAULT_SERVER_ALIVE_INTERVAL = 30_000;

	/**
	 * This code was taken from sun.nio.fs.UnixFileModeAttribute
	 */
	static final int S_IRUSR = 0000400;
	static final int S_IWUSR = 0000200;
	static final int S_IXUSR = 0000100;
	static final int S_IRGRP = 0000040;
	static final int S_IWGRP = 0000020;
	static final int S_IXGRP = 0000010;
	static final int S_IROTH = 0000004;
	static final int S_IWOTH = 0000002;
	static final int S_IXOTH = 0000001;

	static int toUnixMode(PosixFilePermission perm, int mode) {

		switch (perm) {
		case OWNER_READ :     mode |= S_IRUSR; break;
		case OWNER_WRITE :    mode |= S_IWUSR; break;
		case OWNER_EXECUTE :  mode |= S_IXUSR; break;
		case GROUP_READ :     mode |= S_IRGRP; break;
		case GROUP_WRITE :    mode |= S_IWGRP; break;
		case GROUP_EXECUTE :  mode |= S_IXGRP; break;
		case OTHERS_READ :    mode |= S_IROTH; break;
		case OTHERS_WRITE :   mode |= S_IWOTH; break;
		case OTHERS_EXECUTE : mode |= S_IXOTH; break;
		}

		return mode;
	}

	/**
	 * An SSH connection shared by every factory that connects with the same
	 * connection details, credentials and library (see getSessionKey()). Each
	 * factory opens its own SFTP channel over it, because one channel must
	 * not be used by two threads at once.
	 */
	private static class SharedSession {
		final String key;
		final SshConnection connection;
		/** Number of factories holding this connection; guarded by 'sessions'. */
		int refs = 1;

		SharedSession(String key, SshConnection connection) {
			this.key = key;
			this.connection = connection;
		}
	}

	/** Open shared connections by key; all access is synchronized on the map. */
	private static final Map<String,SharedSession> sessions = new HashMap<>();

	/** An SFTP call made on this factory's channel; see sftp(). */
	public interface SftpOperation<T> {
		T run(SftpChannel sftp) throws IOException;
	}

	private String host;
	private String user;
	private String password;
	private String privateKeyFileName;
	private String sessionKey;
	private byte [] privateKey;
	private int port = DEFAULT_PORT;
	private String knownHosts;
	private String strictHostKeyChecking = "no";
	private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
	private int serverAliveInterval = DEFAULT_SERVER_ALIVE_INTERVAL;
	/** "jsch", "mina", or null for the default (see SshProviders). */
	private String implementation;

	/** The shared connection this factory holds a reference to, or null. */
	private SharedSession shared;
	/** This factory's own SFTP channel; only used inside sftp(), which locks the factory. */
	private SftpChannel sftp;

	private FileSource[] roots;
	private FileSource currentDir;

	/**
	 * Bytes per random-access read or write. Each chunk is one round trip, so
	 * bigger is faster over a network; OpenSSH serves up to 256 KB per read.
	 */
	public static final int DEFAULT_CHUNK_SIZE = 32*1024;
	private int chunkSize=DEFAULT_CHUNK_SIZE;

	/** uid -> user name and gid -> group name, learned from directory listings. */
	private final Map<Integer,String> userNames = new ConcurrentHashMap<>();
	private final Map<Integer,String> groupNames = new ConcurrentHashMap<>();

	public SftpFileSourceFactory() {
		super();
	}

	public String getPrivateKeyFileName() {
		return privateKeyFileName;
	}
	public void setPrivateKeyFileName(String privateKeyFileName) {
		this.privateKeyFileName = privateKeyFileName;
	}
	public byte[] getPrivateKey() {
		return privateKey;
	}
	public void setPrivateKey(byte[] privateKey) {
		this.privateKey = privateKey;
	}



	public void setSessionKey(String sessionKey) {
		this.sessionKey = sessionKey;
	}
	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		int idx = host.indexOf(':');
		if( idx > 0 ) {
			String tmp = host.substring(idx+1);
			int i = Integer.parseInt(tmp);
			if( i > 0 ) {
				setPort(i);
			}
			host = host.substring(0, idx);
		}

		this.host = host;
	}

	public String getUser() {
		return user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public String getKnownHosts() {
		return knownHosts;
	}

	/** Path of a known_hosts file; used when strict host key checking is "yes". */
	public void setKnownHosts(String knownHosts) {
		this.knownHosts = knownHosts;
	}

	public String getStrictHostKeyChecking() {
		return strictHostKeyChecking;
	}

	/**
	 * "no" (the default) accepts any host key, which lets a server be
	 * impersonated. "yes" rejects keys that aren't in the known_hosts file.
	 */
	public void setStrictHostKeyChecking(String value) {
		if( value == null || value.trim().isEmpty()) {
			value = "no";
		}
		value = value.trim().toLowerCase();
		if( !value.equals("yes") && !value.equals("no")) {
			throw new IllegalArgumentException("strictHostKeyChecking must be yes or no, not "+value);
		}
		this.strictHostKeyChecking = value;
	}

	public int getConnectTimeout() {
		return connectTimeout;
	}

	/** Milliseconds to wait for the connection, handshake and channel opens (0 = forever). */
	public void setConnectTimeout(int connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	public int getServerAliveInterval() {
		return serverAliveInterval;
	}

	/** Milliseconds between keepalive messages on an idle connection (0 = none). */
	public void setServerAliveInterval(int serverAliveInterval) {
		this.serverAliveInterval = serverAliveInterval;
	}

	/** The configured SSH library ("jsch" or "mina"), or null for the default. */
	public String getImplementation() {
		return implementation;
	}

	/**
	 * Chooses the SSH library: "jsch" or "mina". Null or empty uses the
	 * system property bjl.sftp.implementation, else jsch. Takes effect on the
	 * next connect.
	 *
	 * @throws IllegalArgumentException for an unknown name
	 */
	public void setImplementation(String implementation) {
		if( implementation == null || implementation.trim().isEmpty()) {
			this.implementation = null;
		} else {
			SshProviders.get(implementation);   // validates the name
			this.implementation = implementation.trim().toLowerCase();
		}
	}

	/** The library that will actually be used: the configured one or the default. */
	public String getEffectiveImplementation() {
		return implementation != null ? implementation : SshProviders.defaultName();
	}

	/** The SSH connection this factory uses, connecting first if needed. */
	SshConnection getConnection() throws IOException {
		if( !isConnected()) {
			connect();
		}
		synchronized (this) {
			return shared.connection;
		}
	}

	/**
	 * Runs one SFTP call on this factory's channel, holding the factory's lock
	 * so calls from different threads can't interleave on the channel.
	 * Reconnects first if the channel or connection has closed.
	 */
	public synchronized <T> T sftp(SftpOperation<T> op) throws IOException {
		if( !isConnected()) {
			connect();
		}
		return op.run(sftp);
	}


	public FileSource createFileSource(String path) throws IOException {
		connect();


		if( getCurrentDirectory() != null && !path.startsWith("/")) {
			FileSource file = getCurrentDirectory();
			return file.getChild(path);
		}

		return new SftpFileSource(this, path);
	}

	public void setRoot(String path) {

	}

	@Override
	public FileSource[] listRoots() throws IOException {
		if( roots == null ) {
			roots =new FileSource[1] ;
			roots[0] = createFileSource("/");
		}

		return roots;
	}


	@Override
	public boolean isVersionSupported() {
		return false;
	}


	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	@Override
	public synchronized boolean isConnected() {
		return shared != null && shared.connection.isConnected() && sftp != null && sftp.isOpen();
	}

	/**
	 * Factories with the same key share one SSH connection. Unless a key was
	 * set explicitly, it covers the connection details, the SSH library and a
	 * hash of the credentials, so a factory with different (or wrong)
	 * credentials never reuses another factory's logged-in connection.
	 */
	public String getSessionKey() {
		String ret = sessionKey;
		if( ret == null || ret.isEmpty()) {
			ret = getUser()+"@"+getHost()+":"+getPort()+"#"+credentialHash();
		}

		return ret;
	}

	private String credentialHash() {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			for(String part : new String[] {password, privateKeyFileName, knownHosts, strictHostKeyChecking, getEffectiveImplementation()}) {
				md.update((part == null ? "\0" : part).getBytes(StandardCharsets.UTF_8));
				md.update((byte)0);
			}
			if( privateKey != null ) {
				md.update(privateKey);
			}
			StringBuilder hex = new StringBuilder();
			byte[] d = md.digest();
			for (int i = 0; i < 8; i++) {
				hex.append(String.format("%02x", d[i]));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);   // SHA-256 is always available
		}
	}

	@Override
	protected synchronized boolean connectImpl() throws IOException {
		if( isConnected()) {
			return true;
		}
		if( shared == null || !shared.connection.isConnected()) {
			// no connection yet, or ours has died: drop it and get a live one
			releaseSession();
			shared = acquireSession();
		}
		if( sftp != null ) {
			sftp.close();
		}
		sftp = shared.connection.openSftp();
		return true;
	}

	/** Reuses a live connection with the same key, or connects a new one. */
	private SharedSession acquireSession() throws IOException {
		String key = getSessionKey();
		synchronized (sessions) {
			SharedSession s = sessions.get(key);
			if( s != null && s.connection.isConnected()) {
				s.refs++;
				return s;
			}
			SshProvider provider = SshProviders.get(getEffectiveImplementation());
			logDebug("Connecting to "+getUser()+"@"+getHost()+":"+getPort()+" with "+provider.getName());
			s = new SharedSession(key, provider.connect(settings()));
			sessions.put(key, s);
			return s;
		}
	}

	private SshSettings settings() {
		SshSettings s = new SshSettings();
		s.host = getHost();
		s.port = getPort();
		s.user = getUser();
		s.password = password;
		s.privateKey = privateKey;
		s.privateKeyFile = privateKeyFileName;
		s.knownHosts = knownHosts;
		s.strictHostKeyChecking = "yes".equals(strictHostKeyChecking);
		s.connectTimeoutMs = connectTimeout;
		s.serverAliveIntervalMs = serverAliveInterval;
		return s;
	}

	/** Drops this factory's reference; the last factory to let go closes the connection. */
	private void releaseSession() {
		SharedSession s = shared;
		shared = null;
		if( s == null ) {
			return;
		}
		synchronized (sessions) {
			if( --s.refs <= 0 ) {
				if( sessions.get(s.key) == s ) {
					sessions.remove(s.key);
				}
				s.connection.close();
			}
		}
	}

	@Override
	public Component getEditPropertiesComponent() {

		return new SftpPropertyEditPanel();
	}

	@Override
	protected synchronized void disConnectImpl() {
		if( sftp != null ) {
			sftp.close();
			sftp = null;
		}
		// safe to call twice: the second call finds shared == null
		releaseSession();
	}

	@Override
	public FileSourceFactory createThreadSafeCopy() {
		// The copy shares the SSH connection (same key) but opens its own SFTP
		// channel, so it can be used from another thread.
		SftpFileSourceFactory ret = new SftpFileSourceFactory();
		ret.host = host;
		ret.user = user;
		ret.password = password;
		ret.port = port;
		ret.privateKeyFileName = privateKeyFileName;
		ret.privateKey = privateKey;
		ret.sessionKey = sessionKey;
		ret.knownHosts = knownHosts;
		ret.strictHostKeyChecking = strictHostKeyChecking;
		ret.connectTimeout = connectTimeout;
		ret.serverAliveInterval = serverAliveInterval;
		ret.implementation = implementation;
		ret.chunkSize = chunkSize;

		return ret;
	}

	@Override
	public Properties getConnectProperties() {
		Properties ret = new Properties();
		ret.setProperty(PROP_USER, user == null ? "":user);
		ret.setProperty(PROP_PASSWORD, password == null ? "":password);
		ret.setProperty(PROP_HOST, host == null ? "":host);
		ret.setProperty(PROP_PORT, port <=0 ? ""+DEFAULT_PORT:""+port);
		ret.setProperty(PROP_PRIVATE_KEY_FILE_NAME, privateKeyFileName == null ? "":privateKeyFileName);
		ret.setProperty(PROP_PRIVATE_KEY, privateKey == null ? "":new String(privateKey));
		ret.setProperty(PROP_SESSION_KEY, sessionKey == null ? "":sessionKey);
		ret.setProperty(PROP_KNOWN_HOSTS, knownHosts == null ? "":knownHosts);
		ret.setProperty(PROP_STRICT_HOST_KEY_CHECKING, strictHostKeyChecking);
		ret.setProperty(PROP_CONNECT_TIMEOUT, ""+connectTimeout);
		ret.setProperty(PROP_SERVER_ALIVE_INTERVAL, ""+serverAliveInterval);
		ret.setProperty(PROP_IMPLEMENTATION, implementation == null ? "":implementation);

		return ret;
	}



	@Override
	public void setConnectionProperties(URL url) {
		//  sftp://user:password@host:port/path

		String auth = url.getAuthority();



		if( auth == null ) {
			synchronized (sessions) {
				// if we have a session key, connect will be ok.
				if( sessions.size() > 0 && (sessionKey == null || sessionKey.isEmpty())) {
					if( sessions.size() > 1) {
						throw new RuntimeException("URL has no authority and there are too many open sessions to pick from");
					}
					sessionKey = sessions.keySet().iterator().next();
				}
			}
		} else {
			String parts[] = auth.split("[@]");
			if( parts.length > 1) {
				auth = parts[1];
				parts = parts[0].split("[:]");
				setUser(parts[0]);
				if( parts.length>1) {
					setPassword(parts[1]);
				}
			}
			// now only host & port left
			parts = auth.split("[:]");
			setHost(parts[0]);
			if( parts.length>1) {
				setPort(Integer.parseInt(parts[1]));
			}
		}
	}

	@Override
	public void setConnectionProperties(Properties p) {
		String h = p.getProperty(PROP_HOST,getHost());
		if( h != null ) {
			setHost(h);
		}
		setPort(Integer.parseInt(p.getProperty(PROP_PORT,""+getPort())));
		setUser(p.getProperty(PROP_USER,getUser()));
		setPassword(p.getProperty(PROP_PASSWORD,getPassword()));
		privateKeyFileName = p.getProperty("identityFile");
		String tmp = p.getProperty("privateKey");
		if( tmp != null && !tmp.isEmpty()) {
			privateKey = tmp.getBytes();
		} else {
			privateKey = null;
		}
		String kh = p.getProperty(PROP_KNOWN_HOSTS, knownHosts);
		knownHosts = kh == null || kh.isEmpty() ? null : kh;
		setStrictHostKeyChecking(p.getProperty(PROP_STRICT_HOST_KEY_CHECKING, strictHostKeyChecking));
		connectTimeout = Integer.parseInt(p.getProperty(PROP_CONNECT_TIMEOUT, ""+connectTimeout).trim());
		serverAliveInterval = Integer.parseInt(p.getProperty(PROP_SERVER_ALIVE_INTERVAL, ""+serverAliveInterval).trim());
		setImplementation(p.getProperty(PROP_IMPLEMENTATION, implementation));
	}

	@Override
	public String getTitle() {
		return FACTORY_ID+"://"+getUser()+"@"+getHost()+":"+getPort();
	}

	/** Works while disconnected, and doesn't include the password. */
	@Override
	public String getURL() {
		return FACTORY_ID+"://"+getUser()+"@"+getHost()+":"+getPort();
	}

	/** Lists a directory, remembering the owner and group names it shows. */
	public List<SftpEntry> ls(String path) throws IOException {
		List<SftpEntry> ret = sftp(c -> c.list(path));
		for (SftpEntry e : ret) {
			rememberNames(e);
		}
		return ret;
	}

	/** Attributes of the path itself, not following a link. */
	public SftpAttributes lstat(String path) throws IOException {
		return sftp(c -> c.lstat(path));
	}

	/** Like lstat, but follows symbolic links. */
	public SftpAttributes stat(String path) throws IOException {
		return sftp(c -> c.stat(path));
	}

	/**
	 * Remembers the owner and group names from an entry's ls-style long name
	 * ("-rw-r--r--  1 alice staff  12 Sep 28 12:00 name").
	 */
	void rememberNames(SftpEntry e) {
		String longName = e.getLongname();
		SftpAttributes a = e.getAttrs();
		if( longName == null || a == null ) {
			return;
		}
		String[] parts = longName.trim().split("\\s+");
		if( parts.length >= 4 ) {
			userNames.putIfAbsent(a.getUId(), parts[2]);
			groupNames.putIfAbsent(a.getGId(), parts[3]);
		}
	}

	/** User name for a uid, if a listing has shown it; otherwise null. */
	String userName(int uid) {
		return userNames.get(uid);
	}

	/** Group name for a gid, if a listing has shown it; otherwise null. */
	String groupName(int gid) {
		return groupNames.get(gid);
	}

	public String readlink(String path) throws IOException {
		return sftp(c -> c.readLink(path));
	}

	@Override
	public FileSource getCurrentDirectory() throws  IOException {
		if( currentDir == null ) {
			currentDir = new SftpFileSource(this, sftp(c -> c.home()));
		}
		return currentDir;
	}

	@Override
	public void setCurrentDirectory(FileSource dir) {
		currentDir = dir;

	}

	@Override
	public char getPathSeperatorChar() {
		return ':';
	}

	@Override
	public char getSeperatorChar() {
		return '/';
	}

	public void setChunkSize(int chunk_size) {
		this.chunkSize = chunk_size;;
	}

	public int getChunkSize() {
		return chunkSize;
	}


	FileSourceUser remotePrinciple;

	/**
	 * The remote user, with uid and groups, from running "id" on the server.
	 * Accounts without shell access (internal-sftp, chroot) can't run it; then
	 * the uid and primary group come from the owner of the login directory,
	 * which is the account itself on a normal setup. Its other groups are
	 * unknown in that case.
	 */
	@Override
	public FileSourceUser whoAmI() {
		if( remotePrinciple !=null ) {
			return remotePrinciple;
		}

		try {
			connect();
		} catch (IOException e) {
			logDebug("whoAmI: can't connect: "+e);
			return super.whoAmI();
		}

		try {
			String id = runCommand("id");
			FileSourceUser p = id == null ? null : FileSourceUser.fromId(id);
			if( p !=null ) {
				remotePrinciple = p;
				return p;
			}
		} catch (IOException e) {
			logDebug("whoAmI: 'id' failed, using the login directory's owner: "+e.getMessage());
		}

		try {
			SftpAttributes home = sftp(c -> c.stat(c.home()));
			String groupName = groupName(home.getGId());
			remotePrinciple = new FileSourceUser(home.getUId(), getUser(),
					home.getGId(), groupName == null ? ""+home.getGId() : groupName);
			return remotePrinciple;
		} catch (IOException e) {
			logDebug("whoAmI: can't read the login directory: "+e);
		}

		return super.whoAmI();
	}

	/** How long runCommand waits for a command to finish. */
	private static final long COMMAND_TIMEOUT_MS = 30_000;

	/**
	 * Runs a command over SSH and returns its output (stdout followed by
	 * stderr). Throws if it exits non-zero or doesn't finish within 30 s.
	 * Needs shell access on the server.
	 */
	public String runCommand(String command) throws IOException {
		SshConnection.ExecResult r = getConnection().exec(command, COMMAND_TIMEOUT_MS);
		String output = r.stdout + r.stderr;
		if( r.exitStatus != 0) {
			throw new IOException("status="+r.exitStatus+" ("+output+")");
		}
		return output;
	}

	@Override
	public FileSource createSymbolicLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		sftp(c -> { c.symlink(existingFile.getAbsolutePath(), newFileLink.getAbsolutePath()); return null; });
		existingFile.refresh();
		newFileLink.refresh();
		return newFileLink;
	}

	@Override
	public FileSource createLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		sftp(c -> { c.hardlink(existingFile.getAbsolutePath(), newFileLink.getAbsolutePath()); return null; });
		existingFile.refresh();
		newFileLink.refresh();
		return newFileLink;
	}



}
