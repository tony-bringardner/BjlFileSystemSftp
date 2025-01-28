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
import java.io.InputStream;
import java.net.URL;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceFactory;
import us.bringardner.io.filesource.FileSourceUser;

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
	public static final int DEFAULT_PORT = 22;

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

	private static class SftpSession {
		@SuppressWarnings("unused")
		String key;
		Session session;
		ChannelSftp sftp;
		int isSession = 0;

		public SftpSession(String key,Session session, ChannelSftp sftp) {
			this.key = key;
			this.session = session;
			this.sftp = sftp;
		}


	}

	private static final Map<String,SftpSession> sessions = new HashMap<>();


	private JSch jsch = new JSch() ;
	private String host;
	private String user;
	private String password;
	private String privateKeyFileName;
	private String sessionKey;
	private byte [] privateKey;
	private int port = DEFAULT_PORT;
	private Session session;
	private ChannelSftp sftp;

	private FileSource[] roots;
	private FileSource currentDir;

	private int chunkSize=1024*4;

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
			host = host.substring(0, idx-1);
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

	public Session getSession() throws IOException {
		if( !isConnected()) {
			connect();
		}

		return session;
	}

	public ChannelSftp getSftp_() throws IOException {
		if (!isConnected()) {
			connect();
		}
		return sftp;
	}

	public void setSession(Session session) {
		this.session = session;
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
	public boolean isConnected() {
		return session != null && session.isConnected();
	}

	public String getSessionKey() {
		String ret = sessionKey;
		if( ret == null ) {
			ret = getUser()+"@"+getHost()+":"+getPort();
		}

		return ret;
	}

	@Override
	protected boolean connectImpl() throws IOException {
		boolean ret = isConnected();

		if( !ret ) {
			try {
				String key = getSessionKey();
				SftpSession current = sessions.get(key);
				if( current != null && current.session.isConnected()) {
					if( !current.sftp.isConnected()) {
						current.sftp.connect();
					}
					session = current.session;
					sftp = current.sftp;
					current.isSession++;
					ret = true;
				} else {
					String pw = getPassword();

					logDebug("Connecting to "+key);
					if( privateKey != null ) {
						jsch.addIdentity(null, privateKey, null, null);
					} else if( privateKeyFileName != null && !privateKeyFileName.isEmpty()) {
						jsch.addIdentity(privateKeyFileName);
					}

					session = jsch.getSession(getUser(), getHost(), getPort());

					Properties prop = new Properties();
					prop.put("StrictHostKeyChecking", "no");
					if( pw != null ) {
						session.setPassword(getPassword());
					}
					session.setConfig(prop);
					session.connect();

					sftp = (ChannelSftp) session.openChannel("sftp");
					sftp.connect();
					ret = true;
					sessions.put(key, new SftpSession(key,session,sftp));
				}
			} catch (JSchException e) {
				throw new IOException(e);
			}
		}
		return ret;
	}

	@Override
	public Component getEditPropertiesComponent() {

		return new SftpPropertyEditPanel();
	}

	@Override
	protected void disConnectImpl() {
		if( session != null ) {
			try {
				SftpSession current = sessions.get(getSessionKey());
				if( current != null && --current.isSession <=0) {
					session.disconnect();
					sftp.disconnect();
					sessions.remove(getSessionKey());
				}
			} catch (Exception e) {
			}
			session = null;
			sftp = null;
		}

	}

	@Override
	public FileSourceFactory createThreadSafeCopy() {
		SftpFileSourceFactory ret = new SftpFileSourceFactory();
		ret.host = host;
		ret.user = user;
		ret.password = password;
		ret.port = port;

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

		return ret;
	}



	@Override
	public void setConnectionProperties(URL url) {
		//  sftp://user:password@host:port/path

		String auth = url.getAuthority();



		if( auth == null ) {
			if( sessions.size() > 0) {

				String key = sessionKey;

				// if we have a session key , connect will be ok.
				if( key == null ) {
					key = getConnectProperties().getProperty(PROP_SESSION_KEY);
					if( key == null || key.isEmpty()) {
						if( sessions.size() > 1) {
							throw new RuntimeException("URL has no authority and there are tooo many open sessions to pick from");
						}

						for(String k : sessions.keySet()) {
							sessionKey = k;
							return;
						}
					}

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
		setHost(p.getProperty(PROP_HOST,getHost()));
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

	}

	@Override
	public String getTitle() {
		return FACTORY_ID+"://"+session.getUserName()+"@"+getHost()+":"+getPort();
	}

	@Override
	public String getURL() {
		return FACTORY_ID+"://"+session.getUserName()+":"+getPassword()+"@"+getHost()+":"+getPort();
	}

	@SuppressWarnings("unchecked")
	public synchronized Vector<ChannelSftp.LsEntry> ls(String path) throws IOException, SftpException {
		return getSftp_().ls(path);
	}

	public synchronized SftpATTRS lstat(String path) throws SftpException, IOException {
		return getSftp_().lstat(path);
	}

	public synchronized String readlink(String path) throws SftpException, IOException {
		return getSftp_().readlink(path);
	}

	@Override
	public FileSource getCurrentDirectory() throws  IOException {		
		if( currentDir == null ) {
			try {
				currentDir = new SftpFileSource(this, getSftp_().pwd());
			} catch (SftpException e) {
				throw new IOException(e);
			}
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
	@Override
	public FileSourceUser whoAmI() {
		if( remotePrinciple !=null ) {
			return remotePrinciple;
		}

		if( isConnected()) {
			try {
				String tmp = runCommand("id");
				FileSourceUser p = FileSourceUser.fromId(tmp);
				if( p !=null ) {
					remotePrinciple = p;
					return p;
				}
			} catch (IOException e) {
			}
		}

		return super.whoAmI();
	}

	public String runCommand(String command) throws IOException {
		String ret = null;
		if( isConnected()) {
			ChannelExec execChannel = null;
			try {
				execChannel = (ChannelExec) getSession().openChannel("exec");
				execChannel.setCommand(command);
				InputStream stdOut = execChannel.getInputStream() ;
				InputStream stdErr = execChannel.getErrStream();
				execChannel.connect();
				StringBuilder output = new StringBuilder();
				byte[] buffer = new byte[1024];
				int read;
				while ( ( read = stdOut.read( buffer, 0, buffer.length ) ) >= 0 ) {
					for (int idx = 0; idx < read; idx++) {
						output.append((char)buffer[idx]);
					}
				}
				stdOut.close();
				while ( ( read = stdErr.read( buffer, 0, buffer.length ) ) >= 0 ) {
					for (int idx = 0; idx < read; idx++) {
						output.append((char)buffer[idx]);
					}
				}
				stdErr.close();

				long start = System.currentTimeMillis();
				while(!execChannel.isClosed() && (System.currentTimeMillis()-start)< 2000) {
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
					}
				}
				int status = execChannel.getExitStatus();
				if( status != 0) {
					throw new IOException("status="+status+" ("+output+")");
				}
				ret = output.toString();
			} catch (JSchException  e) {
				throw new IOException(e);
			} finally {
				if( execChannel !=null) {
					try {
						execChannel.disconnect();
					} catch (Exception e2) {
					}
				}
			}
		}
		return ret;
	}



}
