# BjlFileSystemSftp

The SSH/SFTP implementation of the `FileSource` interface from BjlFileSystem
(Bringardner Java Library). Owner: Tony Bringardner. Java 11, Maven, JUnit 5
(plus JUnit 4's `Assert`). Related repos, all under github.com/tony-bringardner:
BjlCore (`bjl_core`), BjlIo (`bjl_io`), BjlFileSystem (`bjl_file_system`),
BjlFileSystemFtp, BjlFileSystemJdbc. They live next to this repo in
`/Volumes/Data/eclipse-git/`.

## How Tony wants work done

- **One git branch per batch of fixes**, named `fix/sftp-review-N` and created
  from `master`. Never commit to `master` directly.
- Tony runs `mvn package` himself, then publishes and merges from GitHub Desktop.
  Don't push. Don't switch the branch he has checked out without saying so.
- `mvn package` must pass with every test running before a batch is called done.
- Each fix gets a test that fails on the old code. Check that by temporarily
  putting the old code back (a quick mutation test), then restore it.
- Commit messages say what changed and why, in plain words.

## Building and testing

- `mvn package` runs the whole suite. At batch 12 that was 128 tests.
- Most tests need a real SSH server on **localhost:22** with these accounts:
  `unittest1` / `0000` (groups `testgroup1`, `testgroup2`), `unittest2`,
  `unittest3`, and `unittest4`, which is SFTP-only and can't run commands.
- `TestSftpRandomAccessIoController` starts an embedded Maverick server on port
  2222 instead.
- Pick the SSH library for a run with `-Dbjl.sftp.implementation=jsch` (the
  default) or `=mina`. Most new tests are parameterized to run with both.
- The core libraries are SNAPSHOT versions. If a build can't find them, run
  `mvn install` in BjlCore, BjlIo and BjlFileSystem first.

## How the code is laid out

- `SftpFileSourceFactory`: connection settings, shared SSH sessions and the
  factory's own SFTP channel. Use the channel only through `sftp(op)`, which
  holds the factory's lock.
  - Sessions are shared between factories with the same user, host, port,
    credentials and library. The key includes a hash of the credentials. The
    reference count starts at 1.
  - Connection properties: `host`, `port`, `user`, `password`, `identityFile`,
    `privateKey`, `implementation`, `strictHostKeyChecking`, `knownHosts`,
    `connectTimeout`, `serverAliveInterval` and `attributeCacheTtl`. A property
    that isn't given leaves the current setting alone; an empty one clears it.
- `SftpFileSource`: one remote path.
  - Directory listings are never cached.
  - A file's own details are cached for `attributeCacheTtl` milliseconds
    (default 2000; 0 always asks the server; negative keeps them until
    `refresh()`).
  - Changes made through the same object are seen at once.
- `SftpRandomAccessIoController` and `SftpSeekableInputStream`: random access
  in chunks (`getChunkSize()`, 32 KB). Each opens its own SFTP channel.
- `client/`: the interface that hides the SSH library. It has `SshProvider`,
  `SshProviders`, `SshConnection`, `SftpChannel`, `SftpFile`,
  `SftpAttributes`, `SftpEntry` and `SshSettings`.
  - `client/jsch/` uses the maintained JSch fork, `com.github.mwiede:jsch`.
  - `client/mina/` uses Apache MINA SSHD, `sshd-sftp`. MINA also needs
    `net.i2p.crypto:eddsa` for Ed25519 keys and known_hosts entries.
  - Code outside `client/` must not use either library directly.
- Errors: a missing file is `NoSuchFileException`, a refused permission is
  `AccessDeniedException`. Opening for random access turns both into
  `FileNotFoundException`, as `RandomAccessFile` does.

## History

The full review is in the claude.ai project "FileSystem", in
`claude/BjlFileSystemSftp-review.md`.

- **Batches 1–10 are merged.** They fixed data-safety bugs, connections and
  sessions, and thread safety. They added the JSch/MINA interface, random
  access through `FileSource` and `java.nio`, caching with a time limit, and
  regression tests. They also removed the private key that was embedded in the
  settings panel.
- **Batch 11 is merged.** It updated `maven-compiler-plugin` from 3.3 to
  3.16.0 and uses `<release>11</release>`, so Maven 3.6.3 or newer is needed.
- **Not code:** the private key that used to be embedded in
  `SftpPropertyEditPanel` was public on GitHub. It must be removed from
  `authorized_keys` on every server that accepts it. Never print it.
- **Batch 12** is done on `fix/sftp-review-12` (below).
- **Still open:** batch 13 below. Also: moving the tests onto the
  embedded server so CI can run them, and the Swing panel pre-filling
  `unittest1` and `localhost`.

## Batch 12: read-ahead for random access (`fix/sftp-review-12`)

**Done on its branch; not merged yet.**

- `MinaSftpFile`: a read after a seek is one request. Once reads move
  forward, they come from MINA's `SftpInputStreamAsync` on the same handle
  (`closeHandle=false`), which keeps requests in flight. Read-ahead starts at
  128 KB and doubles each time it's used up, to 2 MB. That limits the data
  thrown away when a seek closes the reader. The size hint passed to the
  stream (`inEnd - 1`) stops it asking for more.
- The reader is closed on a seek, a write, a truncate, at EOF and on close.
  The read after a write or truncate is a single request again, so reading
  and writing in turn doesn't fetch data ahead and then throw it away.
- `JschSftpFile`: closes its `get` stream at EOF. Before, a read at the old
  end of a file that had since grown kept returning -1.
- `SftpReadAheadTest` checks this with both libraries. Its speed tests go
  through `DelayProxy`, a TCP proxy that adds 25 ms each way. A 2 MB pass
  took 72 round trips with MINA before and takes about 11 now. JSch takes
  about 10.

## Batch 13: a pool of open SFTP channels (`fix/sftp-review-13`)

**Problem.** Every input stream, output stream, random-access file and
seekable stream opens a new SFTP channel through `getConnection().openSftp()`.
That's about 3 round trips: channel open, subsystem request and SFTP init.
Code that opens many small streams, such as copying a folder of small files,
pays that every time.

**Suggested approach:**

- Keep a small pool of idle `SftpChannel`s per shared SSH session, next to the
  reference count.
- To borrow, take an idle channel that is still open, or open a new one.
- When a stream is done with a channel, give it back only if it's still open,
  the stream closed cleanly, and every handle on it is closed. Otherwise close
  the channel.
- Keep at most a few idle channels (for example, 4) and close the rest.
  OpenSSH's default `MaxSessions` is 10 per connection, and that limit counts
  channels in use plus idle ones.
- Close every pooled channel when the session closes, that is, when the last
  factory disconnects.
- Never give one channel to two users at once. Channels aren't thread-safe.
- Optionally, close channels that have sat idle for a while.

**Tests**, with both libraries:

- Opening and closing 50 streams one after another opens only a few channels.
- 8 threads using streams at once get correct data.
- A stream that failed (for example, permission denied) doesn't put its
  channel back in the pool.
- Disconnecting closes the idle channels.
- The 10-channel limit is never hit when 15 streams are opened and closed in a
  row.
