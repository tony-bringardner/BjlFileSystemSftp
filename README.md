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

A factory's own setting wins over the system property. Both libraries behave the same through `FileSource`. With MINA, random access reads and writes at any position in one request each. JSch has no safe way to write at a position, so random access that can write (`rw`, `rws`, `rwd`) goes through a MINA connection to the same account even when JSch is chosen; reading stays on JSch.

Code that needs to talk to the server directly can use the library-neutral interface in `us.bringardner.io.filesource.sftp.client` (`SshProviders`, `SshConnection`, `SftpChannel`, `SftpFile`).

## Other connection properties

| Property | Default | Meaning |
|---|---|---|
| `host`, `port`, `user`, `password` | port 22 | Where and how to log in |
| `identityFile` / `privateKey` | | Private key file path, or the key itself |
| `strictHostKeyChecking` | `yes` | Connect only to servers whose host key is in `knownHosts`; the error says how to add one (`ssh-keyscan`). `no` accepts any server, which lets one be impersonated |
| `knownHosts` | `~/.ssh/known_hosts` | known_hosts file used when checking is on |
| `connectTimeout` | 30000 | Milliseconds for connect, handshake and login |
| `serverAliveInterval` | 30000 | Milliseconds between keepalives; 0 turns them off |
| `attributeCacheTtl` | 2000 | Milliseconds a file's details are cached; see below |

## Caching

Directory listings are never cached: every `listFiles()` asks the server, like `java.io.File`, so a listing always shows changes made through other objects or by other programs. The listing carries each child's details, so calling `isDirectory()` or `length()` on its results costs nothing more.

A file's own details (whether it exists, its size, times, type and owner) are cached for `attributeCacheTtl` milliseconds, then fetched again:

| Value | Behaviour |
|---|---|
| `2000` (default) | Trusted for 2 seconds |
| `0` | Always asks the server, exactly like `java.io.File` |
| negative, e.g. `-1` | Kept until `refresh()` |

Changes made through the same `FileSource` object (writing, deleting, renaming, changing permissions) are always seen at once, whatever the setting.

Set it per factory with `setAttributeCacheTtl(millis)` or the `attributeCacheTtl` connection property, or for the whole JVM with `-Dbjl.sftp.attributeCacheTtl=0`. A factory's own setting wins; changing it applies at once, to files already created too.
