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
 * Idle SFTP channels on one shared SSH connection. Opening a channel takes
 * about 3 round trips (channel open, subsystem request, SFTP init), so a
 * stream that's done with its channel gives it back here for the next one.
 * <p>
 * A channel is only ever with one user: borrow() hands it out and removes it
 * from the pool, and it comes back only through {@link PooledSftpChannel#close()},
 * which returns it only if it's still open, nothing on it failed, and
 * everything opened on it was closed.
 * <p>
 * At most MAX_IDLE channels are kept; OpenSSH allows 10 channels per
 * connection (MaxSessions), counting busy and idle ones. A channel idle for
 * longer than MAX_IDLE_NANOS is closed the next time the pool is used.
 * close() closes the idle channels; the factory calls it when the last
 * factory lets go of the connection.
 */
class SftpChannelPool {

	static final int MAX_IDLE = 4;
	static final long MAX_IDLE_NANOS = TimeUnit.SECONDS.toNanos(60);

	private static class Idle {
		final SftpChannel channel;
		final long since;

		Idle(SftpChannel channel) {
			this.channel = channel;
			this.since = System.nanoTime();
		}
	}

	private final SshConnection connection;
	/** Most recently returned first; guarded by 'this'. */
	private final ArrayDeque<Idle> idle = new ArrayDeque<>();
	private boolean closed;
	private final AtomicInteger opened = new AtomicInteger();

	SftpChannelPool(SshConnection connection) {
		this.connection = connection;
	}

	/**
	 * An idle channel that's still open, or a new one. Close it when done;
	 * that gives it back.
	 */
	SftpChannel borrow() throws IOException {
		SftpChannel ch = null;
		List<SftpChannel> dead = new ArrayList<>();
		synchronized (this) {
			if( closed ) {
				throw new IOException("SSH connection closed");
			}
			expire(dead);
			Idle i;
			while( ch == null && (i = idle.pollFirst()) != null ) {
				if( i.channel.isOpen()) {
					ch = i.channel;
				} else {
					dead.add(i.channel);
				}
			}
		}
		closeAll(dead);
		if( ch == null ) {
			ch = connection.openSftp();
			opened.incrementAndGet();
		}
		return new PooledSftpChannel(this, ch);
	}

	/** Keeps the channel for the next borrow() if 'reusable' and there's room; otherwise closes it. */
	void giveBack(SftpChannel ch, boolean reusable) {
		boolean kept = false;
		List<SftpChannel> dead = new ArrayList<>();
		synchronized (this) {
			expire(dead);
			if( reusable && !closed && idle.size() < MAX_IDLE && ch.isOpen()) {
				idle.addFirst(new Idle(ch));
				kept = true;
			}
		}
		if( !kept ) {
			ch.close();
		}
		closeAll(dead);
	}

	/** Moves channels idle too long into 'dead'; the oldest are at the end. */
	private void expire(List<SftpChannel> dead) {
		long now = System.nanoTime();
		while( !idle.isEmpty() && now - idle.peekLast().since > MAX_IDLE_NANOS ) {
			dead.add(idle.pollLast().channel);
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
			idle.clear();
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
}
