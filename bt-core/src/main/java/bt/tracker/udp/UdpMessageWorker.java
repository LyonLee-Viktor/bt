/*
 * Copyright (c) 2016—2021 Andrei Tomashpolskiy and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bt.tracker.udp;

import bt.BtException;
import bt.protocol.Protocols;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class UdpMessageWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(UdpMessageWorker.class);

    private static final int MIN_MESSAGE_LENGTH = 16;
    private static final int MESSAGE_TYPE_OFFSET = 0;
    private static final int MESSAGE_ID_OFFSET = 4;
    private static final int ERROR_MESSAGE_TYPE = 3;
    private static final int DATA_OFFSET = 8;

    private final SocketAddress localAddress;
    private final SocketAddress remoteAddress;

    private volatile boolean shutdown;
    private volatile DatagramSocket socket;
    private final Object lock;

    private final ExecutorService executor;

    private volatile Session session;

    public UdpMessageWorker(SocketAddress localAddress,
                            SocketAddress remoteAddress,
                            int listeningPort) {
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.lock = new Object();

        String threadName = String.format("%d.bt.tracker.udp.message-worker", listeningPort);
        this.executor = Executors.newSingleThreadExecutor(r -> new Thread(r, threadName));
    }

    public synchronized <T> T sendMessage(UdpTrackerMessage message, UdpTrackerResponseHandler<T> responseHandler,
                                          Duration trackerTimeout) {
        return sendMessage(message, getSession(trackerTimeout), responseHandler, trackerTimeout);
    }

    private Session getSession(Duration trackerTimeout) {
        if (session == null || session.isExpired()) {
            session = createSession(trackerTimeout);
        }
        return session;
    }

    private Session createSession(Duration trackerTimeout) {
        return sendMessage(new ConnectRequest(), Session.noSession(), ConnectResponseHandler.handler(), trackerTimeout);
    }

    private <T> T sendMessage(UdpTrackerMessage message, Session session,
                              UdpTrackerResponseHandler<T> responseHandler,
                              Duration maxTimeout) {
        int lastRetry = 7;
        for (int retryNum = 0; retryNum <= lastRetry; retryNum++) {
            final int timeout = computeTimeout(retryNum);
            try {
                return CompletableFuture.supplyAsync(() ->
                        doSend(message, session, responseHandler), executor).get(timeout, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new BtException("Unexpectedly interrupted while waiting for response from the tracker", e);
            } catch (TimeoutException e) {
                if (retryNum == lastRetry || null != maxTimeout && timeout > maxTimeout.getSeconds()) {
                    throw new BtException("Failed to receive response from the tracker", e);
                }
            } catch (Throwable e) {
                throw new BtException("Failed to receive response from the tracker", e);
            }
        }
        // should not get here.
        throw new BtException("Failed to receive response from the tracker");
    }

    private int computeTimeout(int retryNum) {
        // as per BEP-0015, formula is 15 * 2^retryNum
        return 15 << retryNum;
    }

    private <T> T doSend(UdpTrackerMessage message, Session session, UdpTrackerResponseHandler<T> responseHandler) {
        DatagramSocket socket = getSocket();
        try {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("[Session {}] Sending message {} to remote address: {}",
                        session.getId(), message, remoteAddress);
            }
            socket.send(serialize(message, session));
            DatagramPacket response = new DatagramPacket(new byte[8192], 8192);

            while (true) {
                socket.receive(response);

                if (!remoteAddress.equals(response.getSocketAddress())) {
                    // ignore packets received from unexpected senders
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("[Session {}] Received unexpected datagram packet from remote address: {}",
                                session.getId(), response.getSocketAddress());
                    }
                    continue;
                }

                if (response.getLength() >= MIN_MESSAGE_LENGTH) {
                    byte[] data = response.getData();

                    int messageType = Protocols.readInt(data, MESSAGE_TYPE_OFFSET);
                    if (messageType == ERROR_MESSAGE_TYPE) {
                        String error = new String(Arrays.copyOfRange(data, DATA_OFFSET, response.getLength()), StandardCharsets.US_ASCII);
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace("[Session {}] Received error from remote address: {}; " +
                                            "message ID: {}, messageType: {}, error: {}",
                                    session.getId(), remoteAddress, message.getId(), messageType, error);
                        }
                        return responseHandler.onError(error);
                    } else if (messageType != message.getMessageType()) {
                        // ignore messages with incorrect type
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace("[Session {}] Received message with incorrect type " +
                                            "from remote address: {}; expected: {}, actual: {}",
                                    session.getId(), remoteAddress, message.getMessageType(), messageType);
                        }
                        continue;
                    }

                    int messageId = Protocols.readInt(data, MESSAGE_ID_OFFSET);
                    if (messageId != message.getId()) {
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace("[Session {}] Received message with incorrect message ID " +
                                            "from remote address: {}; expected: {}, actual: {}",
                                    session.getId(), remoteAddress, message.getId(), messageId);
                        }
                        continue;
                    }

                    T result = responseHandler.onSuccess(Arrays.copyOfRange(data, DATA_OFFSET, response.getLength()));
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("[Session {}] Received response " +
                                        "from remote address: {}; message ID: {}, messageType: {}, result: {}",
                                session.getId(), remoteAddress, messageId, messageType, result);
                    }
                    return result;

                } else if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("[Session {}] Received message with incorrect size " +
                                    "from remote address: {}; expected: at least {} bytes, actual: {} bytes",
                            session.getId(), remoteAddress, MIN_MESSAGE_LENGTH, response.getLength());
                }
            }
        } catch (IOException e) {
            throw new BtException("Interaction with the tracker failed {remoteAddress=" + remoteAddress + "}", e);
        }
    }

    private DatagramSocket getSocket() {
        if (shutdown) {
            throw new IllegalStateException("Worker is shutdown");
        }

        if (socket == null || socket.isClosed()) {
            synchronized (lock) {
                if (socket == null || socket.isClosed()) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Creating UDP socket {localAddress=" + localAddress + "}");
                    }
                    socket = createSocket(localAddress);
                }
            }
        }

        if (!socket.isConnected()) {
            synchronized (lock) {
                if (!socket.isConnected()) {
                    try {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Connecting UDP socket {localAddress=" + localAddress +
                                    ", remoteAddress=" + remoteAddress + "}");
                        }
                        socket.connect(remoteAddress);
                    } catch (SocketException e) {
                        throw new BtException("Failed to connect to the tracker {remoteAddress=" + remoteAddress + "}", e);
                    }
                }
            }
        }
        return socket;
    }

    private static DatagramSocket createSocket(SocketAddress address) {
        try {
            return new DatagramSocket(address);
        } catch (SocketException e) {
            throw new BtException("Failed to create socket {localAddress=" + address + "}", e);
        }
    }

    private DatagramPacket serialize(UdpTrackerMessage message, Session session) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(Protocols.getLongBytes(session.getId()));
            message.writeTo(out);
            return new DatagramPacket(out.toByteArray(), out.size());
        } catch (IOException e) {
            throw new BtException("Failed to serialize message", e);
        }
    }

    public void shutdown() {
        synchronized (lock) {
            if (!shutdown) {
                shutdown = true;
                executor.shutdownNow();
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }
}
