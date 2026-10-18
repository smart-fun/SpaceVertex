/*
 * Copyright (c) 2016–2025 Arnaud GUYON
 * This source code is licensed under the MIT License.
 * See LICENSE file for details.
 */
package fr.arnaudguyon.spacevertex.network;

import androidx.annotation.NonNull;

import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import fr.arnaudguyon.spacevertex.network.wifi.WifiGameDevice;

// TODO: rename to something like WebSocketGameConnection
public abstract class WebSocketHelper implements GameConnection {

    private static final String TAG = "WebSocketHelper";
    public static final int NETWORK_VERSION = 2;

    private final @NonNull ArrayList<MessageListenerInfo> mMessageListeners = new ArrayList<>();         // People registered for messages to be sent asap and not stacked (cannot mix stack + registered type)
    protected boolean mDisconnecting = false;
    protected String mServerIp;
    protected PlayerConnectionListener listener;

    static class MessageListenerInfo {
        PackMsg.MsgType mType;
        MessageListener mListener;

        MessageListenerInfo(PackMsg.MsgType type, MessageListener listener) {
            mType = type;
            mListener = listener;
        }
    }

    public WebSocketHelper() {
    }

    public static int getNetworkVersion() {
        return NETWORK_VERSION;
    }

    public void disconnect() {
        mDisconnecting = true;
        synchronized (this) {
            mMessageListeners.clear();
        }
        close();
    }

    @Override
    public void registerConnectionListener(@NonNull PlayerConnectionListener listener) {
        this.listener = listener;
    }

    @Override
    public void unregisterConnectionListener() {
        this.listener = null;
    }

    @Override
    public void addMessageListener(@NonNull PackMsg.MsgType type, @NonNull MessageListener listener) {
        MessageListenerInfo info = new MessageListenerInfo(type, listener);
        synchronized (this) {
            mMessageListeners.add(info);
        }
    }

    @Override
    public void removeMessageListeners() {
        synchronized (this) {
            mMessageListeners.clear();
        }
    }

    public boolean isServer() {
        return (this instanceof SocketServer);
    }

    protected void logMessage(String prefix, @NonNull PackMsg message) {
        PackMsg.MsgType type = message.getType();
        if ((type != PackMsg.MsgType.SHIP_INFO) && (type != PackMsg.MsgType.SHIP_FIRE) && (type != PackMsg.MsgType.SHIP_SCORE) && (type != PackMsg.MsgType.GAME_CHRONO)) {
            Log.i(TAG, prefix + message.getType().name());
        }
    }

    protected void notifyMessage(WebSocketHelper socketHelper, byte[] message, WebSocket client) {

        WifiGameDevice gameDevice = new WifiGameDevice(client);
        PackMsg packMsg = PackMsg.create(message, gameDevice);
        if (packMsg != null) {
            // check if someone has been registered for this kind of messages
            logMessage("receive Message ", packMsg);

            final ArrayList<MessageListenerInfo> listeners;
            synchronized (this) {
                listeners = new ArrayList<>(mMessageListeners);  // avoid concurrent access with message sent et receiver which unregisters
            }
            PackMsg.MsgType type = packMsg.getType();
            for (MessageListenerInfo messageListenerInfo : listeners) {
                if (messageListenerInfo.mType.equals(type)) {
                    messageListenerInfo.mListener.onMessageReceived(socketHelper, packMsg);
                }
            }
        }
    }

    protected abstract void close();

    public String getServerAddress() {
        return mServerIp;
    }

    public String getLocalAddress(WebSocket connection) {
        InetSocketAddress socketAddress = connection.getLocalSocketAddress();
        return (socketAddress != null) ? getAddress(socketAddress) : "";
    }

    public abstract String getRemoteAddress(WebSocket connection);

//    public abstract String getLocalAddress();   // for only connection available, TODO: add an index or ID
//    public abstract String getRemoteAddress();

    protected String getAddress(InetSocketAddress socketAddress) {
        InetAddress address = socketAddress.getAddress();
        return address.getHostAddress();
    }

    // ******************************** SERVER ********************************

    public static class SocketServer extends WebSocketHelper {

        private static final String TAG = "SocketServer";
        private final @NonNull WebSocketServer mSocketServer;
        private final @NonNull HashMap<String, WifiGameDevice> connectedDevices = new HashMap<>(); // String is ? mac/ip?

        public SocketServer(String serverIp, int port) {
            super();

            mServerIp = serverIp;
            Log.i(TAG, "Creates SocketServer " + serverIp + ":" + port);

            InetSocketAddress address = new InetSocketAddress(port);

            mSocketServer = new WebSocketServer(address, Collections.singletonList(new Draft_6455())) {
                @Override
                public void onOpen(WebSocket connection, ClientHandshake handshake) {
                    Log.d(TAG, "onOpen " + connection);

                    String remoteAddress = connection.getRemoteSocketAddress().getAddress().toString();
                    Log.w(TAG, "onOpen REMOTE ADDRESS = " + remoteAddress);
                    connectedDevices.put(remoteAddress, new WifiGameDevice(connection));
                    if (listener != null) {
                        listener.onPlayerJoined(SocketServer.this, new WifiGameDevice(connection));
                    }
                }

                @Override
                public void onClose(WebSocket connection, int code, String reason, boolean remote) {
                    Log.d(TAG, "onClose " + connection);
                    String remoteAddress = connection.getRemoteSocketAddress().getAddress().toString();
                    Log.w(TAG, "onOpen REMOTE ADDRESS = " + remoteAddress);
                    connectedDevices.remove(remoteAddress);
                    if (listener != null) {
                        listener.onPlayerLeft(SocketServer.this, new WifiGameDevice(connection));
                    }
                }

                @Override
                public void onStart() {
                    Log.d(TAG, "onStart");
                }

                @Override
                public void onMessage(WebSocket conn, String message) {
                }

                @Override
                public void onMessage(WebSocket connection, ByteBuffer message) {
                    notifyMessage(SocketServer.this, message.array(), connection);
                }

                @Override
                public void onError(WebSocket connection, Exception exception) {
                    String message = exception.getMessage();
                    Log.e(TAG, "onError " + message);
                    if (listener != null) {
                        listener.onConnectionError(SocketServer.this, new WifiGameDevice(connection), message);
                    }
                }
            };
            mSocketServer.start();
        }

        @Override
        public void sendMessage(@NonNull PackMsg message) {
            logMessage("Server sendMessage ", message);
            synchronized (this) {
                WifiGameDevice messageDevice = (WifiGameDevice) message.getDevice();
                WebSocket destinationSocket = messageDevice.getDevice();
                if (destinationSocket.isOpen()) {
                    destinationSocket.send(message.getBuffer());
                }
            }
        }

        @Override
        protected void close() {
            try {
                mSocketServer.stop();
            } catch (InterruptedException e) {
                Log.e(TAG, "SocketServer error " + e.getMessage());
            }
        }

        @Override
        public String getRemoteAddress(WebSocket connection) {
            InetSocketAddress socketAddress = connection.getRemoteSocketAddress();
            return (socketAddress != null) ? getAddress(socketAddress) : "";
        }

        @Override
        public void disconnect() {
            Log.i(TAG, "disconnecting SocketServer");
            super.disconnect();
        }

    }

    public static class SocketClient extends WebSocketHelper {

        private static final String TAG = "SocketClient";
        private final @NonNull WebSocketClient mSocketClient;
        boolean mConnected = false;
        private final int mServerPort;

        public SocketClient(String serverIp, int serverPort) {
            super();

            mServerIp = serverIp;
            mServerPort = serverPort;

            String url = "ws://" + serverIp + ":" + serverPort;
            Log.d(TAG, "Creates SocketClient to server " + url);
            URI serverUri = URI.create(url);
            mSocketClient = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.i(TAG, "onOpen");
                    mConnected = true;
                    if (listener != null) {
                        listener.onPlayerJoined(SocketClient.this, new WifiGameDevice(mSocketClient.getConnection()));
                    }
                }

                @Override
                public void onMessage(String message) {
                }

                @Override
                public void onMessage(ByteBuffer message) {
                    notifyMessage(SocketClient.this, message.array(), mSocketClient.getConnection());
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.i(TAG, "onClose: " + reason + ", remote:" + remote);
                    boolean wasConnected = mConnected;
                    mConnected = false;
                    if (wasConnected) {
                        WebSocket socket = mSocketClient.getConnection();
                        if (listener != null) {
                            listener.onPlayerLeft(SocketClient.this, new WifiGameDevice(socket));
                        }
                    }
                }

                @Override
                public void onError(Exception exception) {
                    String message = exception.getMessage();
                    Log.d(TAG, "onError: " + message);  // TODO: handle ECONNREFUSED
                    Log.d(TAG, "ex:" + exception);
                    if (listener != null) {
                        WebSocket socket = mSocketClient.getConnection();
                        if (listener != null) {
                            listener.onConnectionError(SocketClient.this, new WifiGameDevice(socket), message);
                        }
                    }
                }
            };
            mSocketClient.connect();
        }

        @Override
        public void sendMessage(@NonNull PackMsg message) {
            logMessage("Client sendMessage ", message);
            synchronized (this) {
                if (mConnected) {
                    try {
                        mSocketClient.send(message.getBuffer());
                    } catch(WebsocketNotConnectedException e) {
                        Log.w(TAG, "WebsocketNotConnectedException sendMessage " + message.getType().name());
                    }
                }
            }
        }

        @Override
        protected void close() {
            mSocketClient.close();
        }

        public int getServerPort() {
            return mServerPort;
        }

        @Override
        public String getRemoteAddress(WebSocket connection) {
            return getServerAddress();
        }

    }

}
