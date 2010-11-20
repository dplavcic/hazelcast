/* 
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.client;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class OutRunnable extends IORunnable {
    final PacketWriter writer;
    final BlockingQueue<Call> queue = new LinkedBlockingQueue<Call>();

    private Connection connection = null;

    final AtomicBoolean reconnection;

    private final Collection<Call> reconnectionCalls = new LinkedBlockingQueue<Call>();

    public OutRunnable(final HazelcastClient client, final Map<Long, Call> calls, final PacketWriter writer) {
        super(client, calls);
        this.writer = writer;
        this.reconnection = new AtomicBoolean(false);
    }

    protected void customRun() throws InterruptedException {
        if (reconnection.get()) {
            Thread.sleep(50L);
            return;
        }
        Call call = queue.poll(100, TimeUnit.MILLISECONDS);
        try {
            if (call == null) {
                if (reconnectionCalls.size() > 0) {
                    checkOnReconnect(call);
                }
                return;
            }
            if (call != RECONNECT_CALL) {
                callMap.put(call.getId(), call);
            }
            Connection oldConnection = connection;
            connection = client.getConnectionManager().getConnection();
            boolean wrote = false;
            if (restoredConnection(oldConnection, connection)) {
                resubscribe(call, oldConnection);
            } else if (connection != null) {
                if (call != RECONNECT_CALL) {
                    logger.log(Level.FINEST, "Sending: " + call);
                    writer.write(connection, call.getRequest());
                    wrote = true;
                }
            } else {
                enQueue(call);
                clusterIsDown(oldConnection);
                return;
            }
            if (connection != null && wrote) {
                writer.flush(connection);
            }
            if (reconnectionCalls.size() > 0) {
                checkOnReconnect(call);
            }
        } catch (Throwable io) {
            logger.log(Level.WARNING,
                    "OutRunnable [" + connection + "] got exception:" + io.getMessage(), io);
            clusterIsDown(connection);
        }
    }

    private void checkOnReconnect(Call call) {
        try {
            Object response = reconnectionCalls.contains(call) ?
                    call.getResponse(100L, TimeUnit.MILLISECONDS) :
                    null;
            if (response != null) {
                reconnectionCalls.remove(call);
            } else {
                for (final Iterator<Call> it = reconnectionCalls.iterator(); it.hasNext();) {
                    final Call c = it.next();
                    response = !c.hasResponse() ? c.getResponse(100L, TimeUnit.MILLISECONDS) : Boolean.TRUE;
                    if (response != null) {
                        it.remove();
                    }
                }
            }
        } catch (Throwable e) {
            // nothing to do
        }
        if (reconnectionCalls.size() == 0) {
            client.getConnectionManager().notifyConnectionIsOpened();
        }
    }

    @Override
    public void interruptWaitingCalls() {
        super.interruptWaitingCalls();
        final BlockingQueue<Call> temp = new LinkedBlockingQueue<Call>();
        queue.drainTo(temp);
        clearCalls(temp);
        clearCalls(reconnectionCalls);
        reconnectionCalls.clear();
    }

    private void clearCalls(final Collection<Call> calls) {
        if (calls == null) return;
        for (final Iterator<Call> it = calls.iterator(); it.hasNext();) {
            final Call c = it.next();
            if (c == RECONNECT_CALL) continue;
            c.setResponse(new NoMemberAvailableException());
            it.remove();
        }
    }

    void clusterIsDown(Connection oldConnection) {
        if (!running) {
            return;
        }
        client.getConnectionManager().destroyConnection(oldConnection);
        if (reconnection.compareAndSet(false, true)) {
            client.executor.execute(new Runnable() {
                public void run() {
                    try {
                        final Connection lookForAliveConnection = client.getConnectionManager().lookForAliveConnection();
                        if (lookForAliveConnection == null) {
                            logger.log(Level.WARNING, "lookForAliveConnection is null, reconnection: " + reconnection);
                            if (reconnection.get()) {
                                interruptWaitingCalls();
                            }
                        } else {
                            if (running) {
                                enQueue(RECONNECT_CALL);
                            }
                        }
                    } catch (IOException e) {
                        logger.log(Level.WARNING,
                                Thread.currentThread().getName() + " got exception:" + e.getMessage(), e);
                    } finally {
                        reconnection.compareAndSet(true, false);
                    }
                }
            });
        }
    }

    private void resubscribe(Call call, Connection oldConnection) {
        onDisconnect(oldConnection);
        final BlockingQueue<Call> temp = new LinkedBlockingQueue<Call>();
        queue.drainTo(temp);
        temp.remove(RECONNECT_CALL);
        reconnectionCalls.addAll(client.getListenerManager().getListenerCalls());
        queue.addAll(reconnectionCalls);
        temp.drainTo(queue);
        queue.addAll(callMap.values());
    }

    public void enQueue(Call call) {
        try {
            if (running == false) {
                throw new NoMemberAvailableException("Client is shutdown.");
            }
            logger.log(Level.FINEST, "From " + Thread.currentThread() + ": Enqueue: " + call);
            queue.put(call);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public int getQueueSize() {
        return queue.size();
    }

    boolean sendReconnectCall(Connection connection) {
        if (running && !reconnection.get() && this.connection != connection && !queue.contains(RECONNECT_CALL)) {
            enQueue(RECONNECT_CALL);
            return true;
        }
        return false;
    }

    void write(Connection connection, Packet packet) throws IOException {
        if (running) {
            client.getOutRunnable().writer.write(connection, packet);
            client.getOutRunnable().writer.flush(connection);
        }
    }
}
