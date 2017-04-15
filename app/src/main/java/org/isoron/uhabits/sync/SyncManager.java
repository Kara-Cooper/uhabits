/*
 * Copyright (C) 2016 Álinson Santos Xavier <isoron@gmail.com>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.sync;

import android.content.*;
import android.support.annotation.*;
import android.util.*;

import org.isoron.uhabits.*;
import org.isoron.uhabits.commands.*;
import org.isoron.uhabits.preferences.*;
import org.isoron.uhabits.utils.*;
import org.json.*;

import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.*;
import java.util.*;

import javax.net.ssl.*;

import io.socket.client.*;
import io.socket.client.Socket;
import io.socket.emitter.*;

import static io.socket.client.Socket.*;

public class SyncManager
{
    public static final String EVENT_AUTH = "auth";

    public static final String EVENT_AUTH_OK = "authOK";

    public static final String EVENT_EXECUTE_EVENT = "execute";

    public static final String EVENT_FETCH = "fetch";

    public static final String EVENT_FETCH_OK = "fetchOK";

    public static final String EVENT_POST_EVENT = "postEvent";

    public static final String SYNC_SERVER_URL =
        "https://sync.loophabits.org:4000";

    private static String CLIENT_ID;

    private static String GROUP_KEY;

    @NonNull
    private Socket socket;

    @NonNull
    private LinkedList<Event> pendingConfirmation;

    @NonNull
    private List<Event> pendingEmit;

    private boolean readyToEmit = false;

    private Context context;

    private final Preferences prefs;

    private CommandRunner commandRunner;

    private CommandParser commandParser;

    public SyncManager(@NonNull Context context,
                       @NonNull Preferences prefs,
                       @NonNull CommandRunner commandRunner,
                       @NonNull CommandParser commandParser)
    {
        this.context = context;
        this.prefs = prefs;
        this.commandRunner = commandRunner;
        this.commandParser = commandParser;

        pendingConfirmation = new LinkedList<>();
        pendingEmit = Event.getAll();

        GROUP_KEY = prefs.getSyncKey();
        CLIENT_ID = DatabaseUtils.getRandomId();

        Log.d("SyncManager", CLIENT_ID);

        try
        {
            IO.setDefaultSSLContext(getCACertSSLContext());
            socket = IO.socket(SYNC_SERVER_URL);
            logSocketEvent(socket, EVENT_CONNECT, "Connected");
            logSocketEvent(socket, EVENT_CONNECT_TIMEOUT, "Connect timeout");
            logSocketEvent(socket, EVENT_CONNECTING, "Connecting...");
            logSocketEvent(socket, EVENT_CONNECT_ERROR, "Connect error");
            logSocketEvent(socket, EVENT_DISCONNECT, "Disconnected");
            logSocketEvent(socket, EVENT_RECONNECT, "Reconnected");
            logSocketEvent(socket, EVENT_RECONNECT_ATTEMPT, "Reconnecting...");
            logSocketEvent(socket, EVENT_RECONNECT_ERROR, "Reconnect error");
            logSocketEvent(socket, EVENT_RECONNECT_FAILED, "Reconnect failed");
            logSocketEvent(socket, EVENT_DISCONNECT, "Disconnected");
            logSocketEvent(socket, EVENT_PING, "Ping");
            logSocketEvent(socket, EVENT_PONG, "Pong");

            socket.on(EVENT_CONNECT, new OnConnectListener());
            socket.on(EVENT_DISCONNECT, new OnDisconnectListener());
            socket.on(EVENT_EXECUTE_EVENT, new OnExecuteCommandListener());
            socket.on(EVENT_AUTH_OK, new OnAuthOKListener());
            socket.on(EVENT_FETCH_OK, new OnFetchOKListener());

            socket.connect();
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void close()
    {
        socket.off();
        socket.close();
    }

    public void postCommand(Command command)
    {
        JSONObject msg = command.toJson();
        Long now = new Date().getTime();
        Event e = new Event(command.getId(), now, msg.toString());
        e.save();

        Log.i("SyncManager", "Adding to outbox: " + msg.toString());

        pendingEmit.add(e);
        if (readyToEmit) emitPending();
    }

    private void emitPending()
    {
        try
        {
            for (Event e : pendingEmit)
            {
                Log.i("SyncManager", "Emitting: " + e.message);
                socket.emit(EVENT_POST_EVENT, new JSONObject(e.message));
                pendingConfirmation.add(e);
            }

            pendingEmit.clear();
        }
        catch (JSONException e)
        {
            throw new RuntimeException(e);
        }
    }

    private SSLContext getCACertSSLContext()
    {
        try
        {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream caInput = context.getAssets().open("cacert.pem");
            Certificate ca = cf.generateCertificate(caInput);

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("ca", ca);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);

            return ctx;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private void logSocketEvent(Socket socket, String event, final String msg)
    {
        socket.on(event, args -> Log.i("SyncManager", msg));
    }

    private void updateLastSync(Long timestamp)
    {
        prefs.setLastSync(timestamp + 1);
    }

    private class OnAuthOKListener implements Emitter.Listener
    {
        @Override
        public void call(Object... args)
        {
            Log.i("SyncManager", "Auth OK");
            Log.i("SyncManager", "Requesting commands since last sync");

            Long lastSync = prefs.getLastSync();
            socket.emit(EVENT_FETCH, buildFetchMessage(lastSync));
        }

        private JSONObject buildFetchMessage(Long lastSync)
        {
            try
            {
                JSONObject json = new JSONObject();
                json.put("since", lastSync);
                return json;
            }
            catch (JSONException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private class OnConnectListener implements Emitter.Listener
    {
        @Override
        public void call(Object... args)
        {
            Log.i("SyncManager", "Sending auth message");
            socket.emit(EVENT_AUTH, buildAuthMessage());
        }

        private JSONObject buildAuthMessage()
        {
            try
            {
                JSONObject json = new JSONObject();
                json.put("groupKey", GROUP_KEY);
                json.put("clientId", CLIENT_ID);
                json.put("version", BuildConfig.VERSION_NAME);
                return json;
            }
            catch (JSONException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private class OnDisconnectListener implements Emitter.Listener
    {
        @Override
        public void call(Object... args)
        {
            readyToEmit = false;
        }
    }

    private class OnExecuteCommandListener implements Emitter.Listener
    {
        @Override
        public void call(Object... args)
        {
            try
            {
                Log.d("SyncManager",
                    String.format("Received command: %s", args[0].toString()));
                JSONObject root = new JSONObject(args[0].toString());
                updateLastSync(root.getLong("timestamp"));
                executeCommand(root);
            }
            catch (JSONException e)
            {
                throw new RuntimeException(e);
            }
        }

        private void executeCommand(JSONObject root) throws JSONException
        {
            Command received = commandParser.parse(root);
            for (Event e : pendingConfirmation)
            {
                if (e.serverId.equals(received.getId()))
                {
                    Log.i("SyncManager", "Pending command confirmed");
                    pendingConfirmation.remove(e);
                    e.delete();
                    return;
                }
            }

            Log.d("SyncManager", "Executing received command");
            commandRunner.execute(received, null);
        }
    }

    private class OnFetchOKListener implements Emitter.Listener
    {
        @Override
        public void call(Object... args)
        {
            try
            {
                Log.i("SyncManager", "Fetch OK");

                JSONObject json = (JSONObject) args[0];
                updateLastSync(json.getLong("timestamp"));

                emitPending();
                readyToEmit = true;
            }
            catch (JSONException e)
            {
                throw new RuntimeException(e);
            }
        }
    }
}
