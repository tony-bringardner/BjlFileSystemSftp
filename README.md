# BjlFileSystemSftp
 SSH/SFTP implementation of the FileSource interface

## Choosing the SSH library

Two SSH libraries are included, and which one is used is decided at run time:

| Value | Library |
|---|---|
| `jsch` (default) | [JSch, maintained fork](https://github.com/mwiede/jsch) (`com.github.mwiede:jsch`) |
| `mina` | [Apache MINA SSHD](https://mina.apache.org/sshd-project/) |

Set it per factory with the `implementation` connection property:

```java
SftpFileSourceFactory factory = new SftpFileSourceFactory();
Properties p = factory.getConnectProperties();
p.setProperty("host", "example.com");
p.setProperty("user", "me");
p.setProperty("password", "secret");
p.setProperty("implementation", "mina");   // or "jsch"
factory.setConnectionProperties(p);
```

or for the whole JVM with a system property:

```
java -Dbjl.sftp.implementation=mina ...
```

A factory's own setting wins over the system property. Both libraries behave the same through `FileSource`. With MINA, random access reads and writes at any position in one request each; with JSch, a write at a position is an open, the write and a close, because JSch has no public positional write.

Code that needs to talk to the server directly can use the library-neutral interface in `us.bringardner.io.filesource.sftp.client` (`SshProviders`, `SshConnection`, `SftpChannel`, `SftpFile`).

## Other connection properties

| Property | Default | Meaning |
|---|---|---|
| `host`, `port`, `user`, `password` | port 22 | Where and how to log in |
| `identityFile` / `privateKey` | | Private key file path, or the key itself |
| `strictHostKeyChecking` | `no` | `yes` rejects servers whose key isn't in `knownHosts` |
| `knownHosts` | `~/.ssh/known_hosts` | known_hosts file used when checking is on |
| `connectTimeout` | 30000 | Milliseconds for connect, handshake and login |
| `serverAliveInterval` | 30000 | Milliseconds between keepalives; 0 turns them off |
