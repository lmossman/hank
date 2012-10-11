/**
 *  Copyright 2011 Rapleaf
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.rapleaf.hank.zookeeper;

import com.rapleaf.hank.util.ExponentialBackoff;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.data.Stat;

import java.util.HashSet;
import java.util.Set;

public abstract class WatchedNode<T> {

  private static final Logger LOG = Logger.getLogger(WatchedNode.class);

  private T value;
  private Long previousVersion = null;
  protected final String nodePath;
  private Stat stat = new Stat();
  protected final ZooKeeperPlus zk;
  private final Set<WatchedNodeListener<T>> listeners = new HashSet<WatchedNodeListener<T>>();
  private boolean cancelled = false;

  protected final T initialValue;

  private final Watcher watcher = new Watcher() {
    @Override
    public void process(WatchedEvent event) {
      if (!cancelled) {
        if (event.getState() == KeeperState.SyncConnected) {
          // If connected update data and notify listeners
          try {
            switch (event.getType()) {
              case NodeCreated:
                watchForData();
                break;
              case NodeDeleted:
                // Previous version notified is null, and we will notify with null
                previousVersion = null;
                watchForCreation();
                break;
              case NodeDataChanged:
                watchForData();
                break;
            }
          } catch (KeeperException e) {
            LOG.error("Exception while trying to update our cached value for " + nodePath, e);
          } catch (InterruptedException e) {
            if (LOG.isTraceEnabled()) {
              LOG.trace("Interrupted while trying to update our cached value for " + nodePath, e);
            }
          }
          // Notify of new value if either we didn't notify of any value, or the node has changed
          long currentVersion = stat.getCtime() + stat.getMtime();
          if (previousVersion == null || !previousVersion.equals(currentVersion)) {
            try {
              synchronized (listeners) {
                for (WatchedNodeListener<T> listener : listeners) {
                  listener.onWatchedNodeChange(value);
                }
              }
            } finally {
              previousVersion = currentVersion;
            }
          }
        } else {
          // Not sync connected, do nothing
          if (LOG.isDebugEnabled()) {
            LOG.debug("Not sync connected anymore for watched node " + nodePath);
          }
        }
      }
    }
  };

  /**
   * Start watching a node, optionnaly waiting for it to be created
   *
   * @param zk
   * @param nodePath
   * @param waitForCreation
   * @throws KeeperException
   * @throws InterruptedException
   */
  protected WatchedNode(final ZooKeeperPlus zk, final String nodePath, boolean waitForCreation, boolean create, T initialValue)
      throws KeeperException, InterruptedException {
    this.zk = zk;
    this.nodePath = nodePath;
    this.initialValue = initialValue;
    // Immediately try to load the data, if it fails, then optionally wait
    try {
      watchForData();
    } catch (KeeperException.NoNodeException e) {
      if (create) {
        zk.create(nodePath, encode(initialValue));
      }
      if (waitForCreation) {
        NodeCreationBarrier.block(zk, nodePath);
        watchForData();
      } else {
        watchForCreation();
      }
    }
  }

  public WatchedNode(ZooKeeperPlus zk, String nodePath, boolean waitForCreation, boolean create)
      throws InterruptedException, KeeperException {
    this(zk, nodePath, waitForCreation, create, null);
  }

  public WatchedNode(ZooKeeperPlus zk, String nodePath, boolean waitForCreation)
      throws InterruptedException, KeeperException {
    this(zk, nodePath, waitForCreation, false, null);
  }

  protected abstract T decode(byte[] data);

  protected abstract byte[] encode(T v);

  public void addListener(WatchedNodeListener<T> listener) {
    synchronized (listeners) {
      listeners.add(listener);
    }
  }

  public boolean removeListener(WatchedNodeListener<T> listener) {
    synchronized (listeners) {
      return listeners.remove(listener);
    }
  }

  private void watchForCreation() throws InterruptedException, KeeperException {
    synchronized (this) {
      value = null;
      stat = new Stat();
    }
    zk.exists(nodePath, watcher);
  }

  private void watchForData() throws InterruptedException, KeeperException {
    if (LOG.isTraceEnabled()) {
      LOG.trace(String.format("Getting value for %s", nodePath));
    }
    synchronized (this) {
      value = decode(zk.getData(nodePath, watcher, stat));
    }
  }

  public T get() {
    return value;
  }

  public void set(T v) throws KeeperException, InterruptedException {
    zk.setData(nodePath, encode(v), -1);
  }

  public void update(WatchedNodeUpdater<T> updater) throws InterruptedException, KeeperException {
    ExponentialBackoff backoff = new ExponentialBackoff();
    while (true) {
      try {
        synchronized (this) {
          zk.setData(nodePath, encode(updater.update(value)), stat.getVersion());
        }
      } catch (KeeperException.BadVersionException e) {
        // If we did not update from the latest version, backoff and retry.
        if (LOG.isDebugEnabled()) {
          LOG.debug("Did not have latest version to update node " + nodePath + ". Backing off for " + backoff.getBackoffMs() + " ms");
        }
        backoff.backoff();
        continue;
      }
      break;
    }
  }

  public void cancelWatch() {
    cancelled = true;
  }

  public void create() throws InterruptedException, KeeperException {
    zk.create(nodePath, encode(initialValue));
  }
}
