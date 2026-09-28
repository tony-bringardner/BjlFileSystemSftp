package us.bringardner.io.filesource.sftp.client.mina;

import java.io.IOException;

import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.sftp.client.RawSftpClient;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.helpers.AbstractSftpClientExtension;

/**
 * OpenSSH's "hardlink@openssh.com" SFTP extension. MINA SSHD 2.14 only
 * creates hard links over SFTP version 6, while OpenSSH speaks version 3 and
 * offers this extension instead (JSch uses it too).
 */
class OpenSshHardLink extends AbstractSftpClientExtension {

	static final String NAME = "hardlink@openssh.com";

	OpenSshHardLink(SftpClient client) {
		super(NAME, client, (RawSftpClient) client, client.getServerExtensions());
	}

	/** True if the client can send it and the server announced it. */
	static boolean isAvailable(SftpClient client) {
		return client instanceof RawSftpClient && client.getServerExtensions().containsKey(NAME);
	}

	/** Creates 'link' as a hard link to 'existing'. */
	void link(String existing, String link) throws IOException {
		Buffer buffer = getCommandBuffer(existing.length() + link.length() + 2 * Integer.BYTES);
		buffer.putString(existing);
		buffer.putString(link);
		sendAndCheckExtendedCommandStatus(buffer);
	}
}
