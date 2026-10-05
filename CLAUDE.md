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

- `mvn package` runs the whole suite. At batch 18 that was 194 tests.
- `TestServer` picks the server once per run, by `-Dbjl.sftp.test.server`:
  - `auto` (the default) uses OpenSSH on **localhost:22** if `unittest1` /
    `0000` can log in, otherwise the embedded server.
  - `local` always uses localhost:22.
  - `embedded` always uses an embedded Maverick server: a free port, only
    `unittest1`, home in `target/embedded-sftp/home`. CI gets this mode.
- The OpenSSH accounts: `unittest1` / `0000` (groups `testgroup1`,
  `testgroup2`), `unittest2`, `unittest3`, and `unittest4`, which is
  SFTP-only and can't run commands.
- Tests that need OpenSSH call `TestServer.assumeOpenSsh()`: links, Unix
  permissions, permission denied, the remote user, shell commands, the other
  accounts and the 10-channel limit. On the embedded server they're skipped
  (24 of them at batch 15). On Tony's machine `auto` picks OpenSSH, so every
  test runs.
- New tests connect with `TestServer.connect(impl)`.
- `TestSftpRandomAccessIoController` (port 2222) and `SftpCanonicalPathTest`
  (port 2224) start their own embedded servers.
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
  in chunks (`getChunkSize()`, 128 KB since batch 17). Each borrows its own SFTP channel.
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
- **Not code, done:** the private key that used to be embedded in
  `SftpPropertyEditPanel` was public on GitHub. Tony has removed it from
  `authorized_keys` on the servers that accepted it. Never print it.
- **Batch 12 is merged.** It added read-ahead for random access (below).
- **Batch 13 is merged.** It added a pool of idle SFTP channels (below).
- **Batch 14 is merged.** The settings
  panel (`SftpPropertyEditPanel`) no longer fills in `unittest1` and
  `localhost`; it starts empty except for port 22.
  `SftpPropertyEditPanelTest` checks this.
- **Batch 15 is merged.** Without an OpenSSH server, the tests run on the
  embedded server (see Building and testing).
- **Batch 16 is merged** (below).
- **Batch 17 is merged** (below).
- **Batch 18 is on its branch, not merged yet** (below).
- **Next: batch 19** (planned with Tony, not started): metadata calls
  borrow pooled channels instead of each factory's own, and a channel limit
  per connection. Decisions:
  - New connection property `maxChannels`, configurable, default 8
    (OpenSSH allows 10 per connection). Not part of the session key: the
    first factory to connect sets it for the shared connection.
  - A separate property for how long a borrow waits when every channel is
    in use (not `connectTimeout`); then it fails with a clear message.
  - Streams, random-access files and seekable streams may use at most
    `maxChannels - 2`, so metadata calls can't be starved; `runCommand`
    takes a slot too.
  - `PooledSftpChannel` must keep a channel after a `NoSuchFileException`
    or `AccessDeniedException` on a simple call (stat, list, ...), or every
    `exists()` on a missing file would cost a new channel.
  - Batch 17's `breakChannelOn` and batch 18's `slowCloseDoesNotHoldUpOthers`
    use the factory's `sftp` field, which goes away; both need rework.
- **Deferred:** CI. Tony isn't ready for it. A workflow would have to build
  BjlCore, BjlIo and BjlFileSystem first, because they're unpublished
  SNAPSHOTs. `TestSftpRandomAccessIoController` and `SftpCanonicalPathTest`
  use fixed ports (2222, 2224), which could clash on a shared CI machine.

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

## Batch 16: data-safety and MINA stream speed (`fix/sftp-review-16`)

- `SftpChannel.createNew(path)` creates an empty file only if nothing is
  there and never truncates. MINA sends one exclusive open (`SSH_FXF_EXCL`).
  JSch's public API can't send that flag, so it checks with `lstat`, then
  opens with APPEND (create, no truncate). If another program creates the
  file in between, its data is kept and the call still returns true.
- `createNewFile()` uses it and, like `java.io.File`, returns false if the
  file was already there. It used to trust the cached `exists()` and then
  open with truncate, so a file created elsewhere in the cache window was
  emptied. The "rw" create in `SftpRandomAccessIoController` uses it too.
- `renameTo` returns false unless the destination is on the same server
  account (`isSameFileSystem`). It used to rename to the destination's path
  on *this* server.
- The `can*()` permission getters and `lastAccessTime()` answer false and 0
  for a missing file instead of throwing NullPointerException. The setters
  for the two file times check for null too.
- `MinaSftpChannel.read` (plain `getInputStream`) uses MINA's pipelined
  `SftpInputStreamAsync`, with the file's size as the hint (one extra `stat`
  on the handle). An empty file keeps the one-request-at-a-time reader,
  because a size hint of 0 means "no limit" to MINA. A 2 MB stream through
  `DelayProxy` took 73 round trips before and takes about 9 now.
- `SftpBatch16Test` and three new tests in `SftpReadAheadTest` check this
  with both libraries.

## Batch 17: time limits, round trips, dead connections (`fix/sftp-review-17`)

- `JschConnection.exec`: a timer on one daemon thread closes the channel when
  the time limit runs out, which ends the read. It used to read stdout to EOF
  with no limit first, so a hung command (`id` in `whoAmI`) blocked forever.
  The read must block: JSch's pipe (a JDK `PipedInputStream`) only wakes its
  writer when a blocking read finds it empty, so polling `available()` moved
  32 KB a second. `runCommand(command, timeoutMs)` sets the limit.
- `JschSftpChannel.open` no longer stats first; the read it opens fails the
  same way for a missing file. Opening a stream to read no longer clears the
  file's cached attributes.
- `DEFAULT_CHUNK_SIZE` is 128 KB (was 32 KB). `SftpReadAheadTest` sets its
  factories to 32 KB, since its counts assume that.
- Owner names: when no listing can name a uid/gid, the numbers are
  remembered (`rememberUnknownNames`), so the next file with that owner
  doesn't list its directory again. Listings now `put` names, replacing them.
- `whoAmI` keeps its answer on the shared connection, so other factories on
  it (including `createThreadSafeCopy()`) don't run `id` again.
- MINA sets `HEARTBEAT_NO_REPLY_MAX` to 3 when `serverAliveInterval` is set.
  Before, its keepalives expected no reply, so a silent connection was never
  dropped. JSch already drops it (its ServerAliveCountMax is 1).
- `sftpReadOnly(op)` (stat, lstat, list, readlink, home): if the call fails
  and the factory is no longer connected, it reconnects and tries once more.
  Missing files, refused permissions, answers from a live server, and every
  change (mkdir, rename, ...) are not retried.
- `SftpBatch17Test` checks this with both libraries. It counts server calls
  with a `CountingFactory` subclass, counts JSch stats through
  `client/jsch/JschTestAccess`, breaks the factory's channel with a
  reflection-injected proxy, and silences a connection with
  `DelayProxy.freeze()`. The 4 shell-command tests need OpenSSH.

## Batch 18: connecting and closing outside the global lock (`fix/sftp-review-18`)

- `acquireSession()` held the JVM-wide `sessions` lock for the whole SSH
  connect and login, so one unreachable server held up every factory for up
  to `connectTimeout`. Now the connect runs outside the lock. A
  `CompletableFuture` in the `connecting` map lets factories with the same
  key wait for that connect and share it, or its failure. A failure isn't
  remembered: the next connect tries again.
- `releaseSession()` closes the pool and connection after letting go of the
  lock. MINA waits up to 10 s per channel for the server to confirm a close,
  which used to hold everyone up.
- `SftpBatch18Test` checks this with both libraries. Each test gets its own
  connections through a unique `sessionKey`. The slow-connect test needs
  192.0.2.1 to time out rather than be refused at once; if it's refused, the
  test is skipped. MINA's slow-close test takes about 10 s, because its own
  channel's close must time out first (batch 19 removes that channel).
