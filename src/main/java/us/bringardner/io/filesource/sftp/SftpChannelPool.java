package us.bringardner.io.filesource.sftp;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import us.bringardner.io.filesource.sftp.client.SftpChannel;
import us.bringardner.io.filesource.sftp.client.SshConnection;

/**
 * The SFTP channels on one shared SSH connection. Opening a channel takes
 * about 3 round trips (channel open, subsystem request, SFTP init), so a
 * channel that's done with gives it back here for the next user.
 * <p>
 * A channel is only ever with one user: borrow() hands it out and removes it
 * from the pool, and it comes back only through {@link PooledSftpChannel#close()},
 * which returns it only if it's still open, nothing on it failed, and
 * everything opened on it was closed.
 * <p>
 * <b>The limit.</b> At most maxChannels channels are open at once, busy and
 * idle together, plus the slots taken by {@link #reserveSlot()} for commands:
 * servers limit them (OpenSSH: 10 per connection, MaxSessions). When all are
 * in use, borrow() waits up to waitMs for one to come back, then fails with
 * an IOException that says so, instead of the server refusing the channel.
 * <p>
 * <b>Streams can't starve calls.</b> Streams, random-access files and
 * seekable streams ({@link Use#STREAM}) may hold at most maxChannels - 2
 * channels. Single calls ({@link Use#CALL}: stat, list, mkdir...) may use
 * them all. A call holds its channel for one request and never borrows
 * another meanwhile, so calls always finish, even while every thread holding
 * a stream is waiting on a call.
 * <p>
 * At most MAX_IDLE channels are kept idle. A channel idle for longer than
 * MAX_IDLE_NANOS is closed the next time the pool is used. close() closes the
 * idle channels; the factory calls it when the last factory lets go of the
 * connection.
 */
class SftpChannelPool {

	static final int MAX_IDLE = 4;
	static final long MAX_IDLE_NANOS = TimeUnit.SECONDS.toNanos(60);
	/** Channels only calls can use; see the class comment. */
	static final int RESERVED_FOR_CALLS = 2;

	/** What a borrowed channel is for; see the class comment. */
	enum Use {
		/** A stream, random-access file or seekable stream: may be held for a long time. */
		STREAM,
		/** One request and its answer. */
		CALL
	}

	private static class Idle {
		final SftpChannel channel;
		final long since;

		Idle(SftpChannel channel) {
			this.channel = channel;
			this.since = System.nanoTime();
		}
	}

	private final SshConnection connection;
	private final int maxChannels;
	private final long waitMs;
	/** Most recently returned first; guarded by 'this'. */
	private final ArrayDeque<Idle> idle = new ArrayDeque<>();
	private boolean closed;
	/** Channels open (busy or idle) and slots reserved; guarded by 'this'. */
	private int open;
	/** Channels borrowed for STREAM use; guarded by 'this'. */
	private int streams;
	private final AtomicInteger opened = new AtomicInteger();

	/**
	 * @param maxChannels at most this many channels open at once (at least 1)
	 * @param waitMs how long borrow() waits when all are in use; 0 or less fails at once
	 */
	SftpChannelPool(SshConnection connection, int maxChannels, long waitMs) {
		if( maxChannels < 1 ) {
			throw new IllegalArgumentException("maxChannels must be at least 1, not "+maxChannels);
		}
		this.connection = connection;
		this.maxChannels = maxChannels;
		this.waitMs = waitMs;
	}

	/** A pool with the factory's defaults; for tests. */
	SftpChannelPool(SshConnection connection) {
		this(connection, SftpFileSourceFactory.DEFAULT_MAX_CHANNELS, SftpFileSourceFactory.DEFAULT_CHANNEL_WAIT_TIMEOUT);
	}

	/** Most channels STREAM use may hold. */
	private int streamLimit() {
		return Math.max(1, maxChannels - RESERVED_FOR_CALLS);
	}

	/** A channel for a stream; see borrow(Use). */
	SftpChannel borrow() throws IOException {
		return borrow(Use.STREAM);
	}

	/**
	 * An idle channel that's still open, or a new one if the limit allows,
	 * else the first one given back within waitMs. Close it when done; that
	 * gives it back.
	 *
	 * @throws IOException if none is free within waitMs, or the pool is closed
	 */
	SftpChannel borrow(Use use) throws IOException {
		SftpChannel ch = null;
		List<SftpChannel> dead = new ArrayList<>();
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMs));
		try {
			synchronized (this) {
				while( true ) {
					if( closed ) {
						throw new IOException("SSH connection closed");
					}
					expire(dead);
					if( use == Use.CALL || streams < streamLimit()) {
						Idle i;
						while( ch == null && (i = idle.pollFirst()) != null ) {
							if( i.channel.isOpen()) {
								ch = i.channel;
							} else {
								dead.add(i.channel);
								open--;
							}
						}
						if( ch != null || open < maxChannels ) {
							break;
						}
					}
					waitForRoom(deadline, use);
				}
				if( ch == null ) {
					open++;   // a new one, opened below without the lock
				}
				if( use == Use.STREAM ) {
					streams++;
				}
			}
		} finally {
			closeAll(dead);
		}
		if( ch == null ) {
			try {
				ch = connection.openSftp();
			} catch (IOException | RuntimeException e) {
				synchronized (this) {
					open--;
					if( use == Use.STREAM ) {
						streams--;
					}
					notifyAll();
				}
				throw e;
			}
			opened.incrementAndGet();
		}
		return new PooledSftpChannel(this, ch, use);
	}

	/** Waits on the pool's lock until something comes back, or throws once 'deadline' passes. */
	private void waitForRoom(long deadline, Use use) throws IOException {
		long left = deadline - System.nanoTime();
		if( left <= 0 ) {
			String what = use == Use.STREAM
					? "all "+streamLimit()+" SFTP channels for streams ("+maxChannels+" in all, "
							+RESERVED_FOR_CALLS+" kept for other calls)"
					: "all "+maxChannels+" SFTP channels";
			throw new IOException(what+" on this connection are in use; waited "+waitMs
					+" ms (see the maxChannels and channelWaitTimeout properties)");
		}
		try {
			TimeUnit.NANOSECONDS.timedWait(this, left);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for an SFTP channel");
		}
	}

	/**
	 * A slot for something other than an SFTP channel that the server counts
	 * against the same limit: a command (exec channel). Waits like borrow().
	 * If the limit is reached and channels are idle, one is closed to make
	 * room. Release the slot when the command is done.
	 */
	Runnable reserveSlot() throws IOException {
		List<SftpChannel> dead = new ArrayList<>();
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMs));
		try {
			synchronized (this) {
				while( true ) {
					if( closed ) {
						throw new IOException("SSH connection closed");
					}
					expire(dead);
					if( open >= maxChannels && !idle.isEmpty()) {
						dead.add(idle.pollLast().channel);   // the oldest idle one makes room
						open--;
					}
					if( open < maxChannels ) {
						open++;
						break;
					}
					waitForRoom(deadline, Use.CALL);
				}
			}
		} finally {
			closeAll(dead);
		}
		boolean[] released = {false};
		return () -> {
			synchronized (this) {
				if( !released[0] ) {
					released[0] = true;
					open--;
					notifyAll();
				}
			}
		};
	}

	/** Keeps the channel for the next borrow() if 'reusable' and there's room; otherwise closes it. */
	void giveBack(SftpChannel ch, boolean reusable, Use use) {
		boolean kept = false;
		List<SftpChannel> dead = new ArrayList<>();
		synchronized (this) {
			if( use == Use.STREAM ) {
				streams--;
			}
			expire(dead);
			if( reusable && !closed && idle.size() < MAX_IDLE && ch.isOpen()) {
				idle.addFirst(new Idle(ch));
				kept = true;
			} else {
				open--;
			}
			notifyAll();
		}
		if( !kept ) {
			ch.close();
		}
		closeAll(dead);
	}

	/** Moves channels idle too long into 'dead'; the oldest are at the end. Hold the lock. */
	private void expire(List<SftpChannel> dead) {
		long now = System.nanoTime();
		while( !idle.isEmpty() && now - idle.peekLast().since > MAX_IDLE_NANOS ) {
			dead.add(idle.pollLast().channel);
			open--;
		}
	}

	private static void closeAll(List<SftpChannel> channels) {
		for (SftpChannel c : channels) {
			c.close();
		}
	}

	/** Closes the idle channels. Channels in use are closed, not kept, when they come back. */
	void close() {
		List<SftpChannel> all = new ArrayList<>();
		synchronized (this) {
			closed = true;
			for (Idle i : idle) {
				all.add(i.channel);
			}
			open -= idle.size();
			idle.clear();
			notifyAll();   // waiting borrowers fail now
		}
		closeAll(all);
	}

	/** The idle channels now; for tests. */
	synchronized List<SftpChannel> idleChannels() {
		List<SftpChannel> ret = new ArrayList<>();
		for (Idle i : idle) {
			ret.add(i.channel);
		}
		return ret;
	}

	/** Channels this pool has opened so far; for tests. */
	int openedCount() {
		return opened.get();
	}

	/** Channels open now (busy and idle) and slots reserved; for tests. */
	synchronized int openCount() {
		return open;
	}

	int maxChannels() {
		return maxChannels;
	}

	/**
	 * Adds a channel opened elsewhere as the next one borrow() hands out, as
	 * if it had been opened and given back here; for tests.
	 */
	synchronized void addIdleForTest(SftpChannel ch) {
		open++;
		idle.addFirst(new Idle(ch));
	}
}
