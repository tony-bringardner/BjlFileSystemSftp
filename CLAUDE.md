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

- `mvn package` runs the whole suite. At batch 13 that was 146 tests.
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
  in chunks (`getChunkSize()`, 32 KB). Each borrows its own SFTP channel.
- Streams, random-access files and seekable streams get their channel from
  `factory.openSftp()`, not `getConnection().openSftp()`. It borrows from the
  shared session's `SftpChannelPool` (at most 4 idle). The channel comes back
  as a `PooledSftpChannel`, and closing it returns it to the pool only if no
  call on it threw and every stream or file opened on it was closed.
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
- **Batch 12 is merged.** It added read-ahead for random access (below).
- **Batch 13** is done on `fix/sftp-review-13` (below).
- **Still open:** moving the tests onto the
  embedded server so CI can run them, and the Swing panel pre-filling
  `unittest1` and `localhost`.

## Batch 12: read-ahead for random access (`fix/sftp-review-12`)

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

**Done on its branch; not merged yet.**

- `SftpChannelPool` sits on each `SharedSession`. `borrow()` takes the most
  recently returned idle channel that's still open, or opens a new one. It
  keeps at most 4 idle channels and closes any that have been idle for 60 s
  the next time the pool is used. The pool is closed when the last factory
  lets go of the session.
- `PooledSftpChannel` wraps a borrowed channel. Any exception, even a missing
  file, marks it failed. A channel closed while one of its streams or files
  is still open isn't returned. After `close()` it refuses every call.
- Closing a channel used not to wait for the server, so a channel opened
  right after a close could be refused by OpenSSH's 10-channel limit.
  `MinaSftpChannel.close()` now waits up to 10 s for the close to finish.
  JSch has no way to wait, so `JschConnection.openSftp()` retries a refused
  open for up to 1 s. With JSch, the old code failed the 8-thread test
  this way.
- `SftpChannelPoolTest` checks this with both libraries: 50 streams in a
  row, 15 in a row, the idle limit, borrowing, 8 threads, failed streams, a
  stream left open, close-then-open at the limit, and disconnect. No test
  reliably fails without the MINA close wait. The race showed up once and
  couldn't be reproduced on localhost.
