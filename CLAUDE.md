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

- `mvn package` runs the whole suite. At batch 22 that was 251 tests; at
  batch 23, 366 (the library-parameterized tests run with bjl too).
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
  (36 of them at batch 21). On Tony's machine `auto` picks OpenSSH, so every
  test runs.
- New tests connect with `TestServer.connect(impl)`, or build on
  `TestServer.properties(impl, port)`. Both turn host key checking off
  (`strictHostKeyChecking=no`), since the test servers' keys aren't in any
  known_hosts file and checking is on by default since batch 22. A test
  that makes its own factory must do the same. A test that needs a
  connection of its own (its own pool and limits) sets a unique `sessionKey`
  property; before batch 19 that property was silently ignored.
- `TestSftpRandomAccessIoController` (port 2222) and `SftpCanonicalPathTest`
  (port 2224) start their own embedded servers.
- Pick the SSH library for a run with `-Dbjl.sftp.implementation=jsch` (the
  default), `=mina` or `=bjl`. Most new tests are parameterized to run with
  all three.
- The core libraries are SNAPSHOT versions. If a build can't find them, run
  `mvn install` in BjlCore, BjlIo and BjlFileSystem first.

## How the code is laid out

- `SftpFileSourceFactory`: connection settings and shared SSH sessions. A
  factory has no channel of its own (since batch 19): `sftp(op)` borrows one
  from the shared pool for each call, so calls from different threads run
  at the same time. `op` must not let a stream or file it opens escape.
  - Sessions are shared between factories with the same user, host, port,
    credentials and library. The key includes a hash of the credentials. The
    reference count starts at 1.
  - Connection properties: `host`, `port`, `user`, `password`, `identityFile`,
    `privateKey`, `implementation`, `strictHostKeyChecking`, `knownHosts`,
    `connectTimeout`, `serverAliveInterval`, `attributeCacheTtl`,
    `sessionKey`, `maxChannels`, `channelWaitTimeout` and `safeOverwrite`. A property
    that isn't given leaves the current setting alone; an empty one clears it.
- `SftpFileSource`: one remote path.
  - Directory listings are never cached.
  - A file's own details are cached for `attributeCacheTtl` milliseconds
    (default 2000; 0 always asks the server; negative keeps them until
    `refresh()`).
  - Changes made through the same object are seen at once.
- `SftpRandomAccessIoController` and `SftpSeekableInputStream`: random access
  in chunks (`getChunkSize()`, 128 KB since batch 17). Each borrows its own SFTP channel.
  Random access that can write ("rw", "rws", "rwd", and the one-argument
  constructor) always goes through MINA, even on a JSch factory (batch 20).
- Streams, random-access files and seekable streams get their channel from
  `factory.openSftp()`, not `getConnection().openSftp()`. It borrows from the
  shared session's `SftpChannelPool` (`Use.STREAM`; calls use `Use.CALL`).
  The pool keeps at most 4 idle and at most `maxChannels` open (see batch
  19). The channel comes back as a `PooledSftpChannel`, and closing it
  returns it to the pool only if nothing on it failed and every stream or
  file opened on it was closed. `NoSuchFileException` and
  `AccessDeniedException` from a call don't count as failing.
- `client/`: the interface that hides the SSH library. It has `SshProvider`,
  `SshProviders`, `SshConnection`, `SftpChannel`, `SftpFile`,
  `SftpAttributes`, `SftpEntry` and `SshSettings`.
  - `client/jsch/` uses the maintained JSch fork, `com.github.mwiede:jsch`.
  - `client/mina/` uses Apache MINA SSHD, `sshd-sftp`. MINA also needs
    `net.i2p.crypto:eddsa` for Ed25519 keys and known_hosts entries.
  - `client/bjl/` uses BJL's own SSH library, `us.bringardner:bjl_net_ssh`
    (the BjlSsh repo). It needs `mvn install` in BjlSsh (and BjlNetFramework)
    first while they're SNAPSHOTs.
  - Code outside `client/` must not use any of the libraries directly.
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
- **Batch 18 is merged** (below).
- **Batch 19 is merged** (below).
- **Batch 20 is merged** (below).
- **Batch 21 is merged** (below).
- **Batch 22 is merged** (below).
- **Batch 23 is on its branch, not merged yet** (below).
- **Skipped, Tony's call:** #18, one MINA `SshClient` for all connections.
  Measured: no difference in connect time (about 260 ms, nearly all login)
  or transfer speed; it saves about 9 idle threads per MINA connection on a
  12-CPU machine. Only worth it for dozens of servers at once.
- **Seen once, not fixed:** in one full run, `TestSftpRandomAccessIoController
  .testWriteRandomcIo` hung forever in JSch's `put` stream `close()`, waiting
  for the embedded server's reply to the last write. It passed 6 times alone
  and in the next full runs. JSch waits for that reply with no time limit;
  keepalives don't help when the server is alive but doesn't answer. Use
  `-Dsurefire.timeout=540` so a hang fails the run instead of stalling it.
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

## Batch 19: calls on pooled channels and a channel limit (`fix/sftp-review-19`)

- The factory's own channel is gone. `sftp(op)` and `sftpReadOnly(op)` borrow
  from the pool (`Use.CALL`) for each call, without the factory's lock.
  Before, every call from every thread queued for the factory's one channel,
  and each factory (and each `createThreadSafeCopy()`) kept that channel
  open: twelve copies on one connection hit OpenSSH's limit of 10.
  `isConnected()` now means the shared connection is up. `connectImpl()`
  borrows and returns one channel, so a server without SFTP still fails at
  connect. `shared`, `roots`, `currentDir` and `remotePrinciple` are volatile.
- `sftpReadOnly` retries when its channel was lost (`!c.isOpen()`, checked
  before close) or the connection dropped.
- `PooledSftpChannel`: a call's `NoSuchFileException` or
  `AccessDeniedException` no longer marks the channel failed. They're the
  server's answer, and `exists()` on a missing file would otherwise cost a
  new channel each time. Streams and files keep the strict rule.
- The limit, in `SftpChannelPool`: at most `maxChannels` (connection
  property, default 8) channels open, busy and idle, plus command slots.
  `borrow` waits up to `channelWaitTimeout` (default 30 s; 0 fails at once)
  and then throws an IOException saying the channels are in use. Streams may
  hold at most `maxChannels - 2` (`RESERVED_FOR_CALLS`), so calls can't be
  starved. Below 3 there's no reserve. `runCommand` takes a slot with
  `reserveSlot()`, closing an idle channel if that's what makes room. Both
  settings come from the factory that creates the connection; they're not
  in the session key.
- `JschConnection.exec` retries a refused channel open for up to 1 s, like
  `openSftp()`: JSch doesn't wait for a close to be confirmed, so the slot
  just freed may still count on the server.
- `setConnectionProperties(Properties)` now reads `sessionKey`, which
  `getConnectProperties()` always wrote. Batch 18's tests set it to get
  connections of their own and silently didn't; now they do.
- Tests: `SftpBatch19Test`. `SftpChannelPoolTest.aFailedStreamDoesNotGiveBackItsChannel`
  became `aRefusedOpenKeepsItsChannel` (the rule changed on purpose).
  Batch 17's `breakChannelOn` plants a broken channel with
  `SftpChannelPool.addIdleForTest`. Batch 18's slow-close test waits for
  `isConnected()` to turn false, and now takes about 10 s less with MINA.
- Measured through `DelayProxy` (50 ms round trip): 4 threads x 5 stats took
  22 round trips with calls serialized, 5 now.

## Batch 20: random-access writes through MINA (`fix/sftp-review-20`)

- JSch's public API can't write at an offset. `JschSftpFile.write` asks the
  file's size and writes with RESUME at `position - size`, so if anyone else
  changed the size in between, the data landed that many bytes away, or
  JSch sent a negative offset (`SSH_FX_BAD_MESSAGE`). Tony chose to route
  around it rather than detect it.
- `SftpFileSourceFactory.randomAccessFactory()`: a MINA factory returns
  itself. A JSch factory makes a MINA copy (`createThreadSafeCopy()` with
  `implementation` "mina"), connected with `connectImpl()` on first use so
  it isn't registered as a factory session, and disconnected in
  `disConnectImpl()`. An explicit `sessionKey` gets "#mina" added, so MINA
  never shares JSch's connection. Its own lock (`randomAccessLock`), not the
  factory's. If MINA can't connect, opening fails with a message saying why
  MINA was used; there's no fallback to JSch.
- `SftpRandomAccessIoController` takes its channel from it for "rw",
  "rws", "rwd" and the one-argument constructor. "r" and
  `SftpSeekableInputStream` stay on JSch: its reads at an offset are safe.
  `JschSftpFile.write` is unchanged, for direct `SftpChannel.open` users.
- `SftpBatch20Test`: while another channel keeps growing the file to 8 KB
  and cutting it to 4 KB, 200 random-access writes are each read back from
  the server. Through JSch it fails on every run, usually on the first
  write. Also: the MINA connection is made only for writing, is
  disconnected with the factory, and gets its own key.


## Batch 21: mkdirs races, dropped factories, safe overwrite (`fix/sftp-review-21`)

- `mkdirs()`: if `mkdir` fails, it asks again and succeeds when the
  directory is there now (another program made it, or `exists()` answered
  "missing" from the cache). It used to throw. A file in the way is now
  `false`, like `java.io.File`; it used to be `true`.
- Dropped factories: the factory's connection lives in a `SessionLink`
  registered with a `Cleaner` (one daemon thread). When a factory is
  garbage collected without `disConnect()`, the Cleaner releases its share
  of the connection, and the last share closes it. It used to stay open
  until the JVM exited. The base class's session registry holds factories
  weakly, so it doesn't keep them alive.
- Serialization: `FileSourceFactory` is `Serializable`. Batch 20's lock (a
  plain `Object`) broke that for SFTP factories; it's a `SerializableLock`
  now. The connection is `transient`, so a connected factory serializes and
  comes back disconnected. `readObject` registers the copy with the Cleaner.
- `safeOverwrite` (connection property, default false): a replacing output
  stream writes `.name.<random>.tmp` beside the target and, on a clean
  `close()`, copies the target's permission bits to it and renames it over
  the target with the new `SftpChannel.replace`. On any failure the
  temporary file is removed and the target is untouched. Through a symbolic
  link it replaces the file the link points to. Append ignores it. Needs
  write permission on the directory; owner, group and hard links aren't
  kept; a stream never closed leaves its temporary file.
- `SftpChannel.replace(from, to)`: atomic with OpenSSH's
  `posix-rename@openssh.com`. JSch's `rename` already uses it when offered;
  MINA uses `OpenSSHPosixRenameExtension`. Without it, `to` is removed first.
- `SftpBatch21Test` checks this with both libraries. The dropped-factory
  test connects in a helper method, so the factory is unreachable once it
  returns, and waits for the connection to close while calling
  `System.gc()`.


## Batch 22: host key checking on by default (`fix/sftp-review-22`)

- `strictHostKeyChecking` defaults to "yes" (`DEFAULT_STRICT_HOST_KEY_CHECKING`);
  empty or null means the default. Before, any server was accepted, so one
  could be impersonated. The known_hosts file is `knownHosts`, else
  `~/.ssh/known_hosts` (`SshProviders.knownHostsFile`, shared by both
  libraries). The batch 20 MINA connection for random-access writes gets the
  same settings.
- A rejected key (unknown or changed) is an IOException from
  `SshProviders.hostKeyRejected`: it names the server and the file and
  suggests `ssh-keyscan -p PORT HOST >> FILE` or `strictHostKeyChecking=no`.
  The libraries' own messages were "reject HostKey: localhost" (JSch) and
  "Server key did not validate" (MINA, for both cases). JSch's is matched by
  message, so the original JSch 0.1.55 is covered too.
- The test helpers set "no" (see Building and testing). README's property
  table says "yes", and its JSch random-access sentence now matches batch 20.
- `SftpBatch22Test`, both libraries: the default refuses an unknown server
  and a changed key with the new message, and connects (including a
  random-access write through MINA) with the server's key in known_hosts.
  The key comes from `ssh-keyscan`, or, when that finds nothing (the
  embedded server), from a MINA key exchange that records it.


## Batch 23: a third SSH library, BJL's own (`fix/sftp-review-23`)

- `implementation=bjl` (or `-Dbjl.sftp.implementation=bjl`) uses
  `us.bringardner:bjl_net_ssh`, BJL's own SSH client (BjlSsh): no third party
  code. `client/bjl/` maps `SftpChannel` and friends onto its `SftpClient`,
  the same way `client/mina/` does onto MINA's: the same errors
  (`NoSuchFileException`, `AccessDeniedException`), `replace` with
  `posix-rename@openssh.com`, `createNew` with one exclusive open, chmod /
  chown / times keeping the other attributes, and close waiting up to 10 s for
  the server to confirm (OpenSSH's 10-channel limit).
- Its streams keep up to 16 requests of 32 KB in flight (reads start at 2 and
  double); random access reads use the same read-ahead from the position.
  Host keys: `KnownHosts` on the same file with `strictHostKeyChecking`;
  keepalives answer like OpenSSH's `ServerAliveCountMax` 3.
- `randomAccessFactory()`: a BJL factory uses itself, like MINA. BJL writes at
  an offset in one request; before, every library but MINA got a MINA
  companion for random-access writes.
- Tests: `SftpBatch23Test`; every test parameterized by library now also runs
  with "bjl" (each class has its own `bjl` factory). The whole suite passed
  with `-Dbjl.sftp.implementation=bjl` against OpenSSH on localhost (259 tests).
- `libraryThreadsDoNotKeepTheProgramAlive` ignores threads named "AWT-...":
  the JDK's AWT-Shutdown thread comes and goes after the Swing panel test and
  failed the check now and then (twice in this batch, once with JSch).

