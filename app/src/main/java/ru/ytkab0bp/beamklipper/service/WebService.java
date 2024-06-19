package ru.ytkab0bp.beamklipper.service;

import static org.nanohttpd.protocols.http.response.Response.newChunkedResponse;
import static org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.request.Method;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;
import org.nanohttpd.protocols.websockets.CloseCode;
import org.nanohttpd.protocols.websockets.NanoWSD;
import org.nanohttpd.protocols.websockets.OpCode;
import org.nanohttpd.protocols.websockets.WebSocket;
import org.nanohttpd.protocols.websockets.WebSocketFrame;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.ytkab0bp.beamklipper.KlipperApp;
import ru.ytkab0bp.beamklipper.R;
import ru.ytkab0bp.beamklipper.utils.Prefs;

public class WebService extends Service {
    public final static int PORT = 8888;
    private final static int ID = 300000;

    private final static Pattern API_PATTERN = Pattern.compile("^/(printer|api|access|machine|server)/");
    private static SharedPreferences mPrefs;
    private static SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ROOT);
    private HttpServer httpServer = new HttpServer();

    private NotificationManager notificationManager;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        Notification.Builder not = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, KlipperApp.SERVICES_CHANNEL) : new Notification.Builder(this));
        not.setContentTitle(getString(R.string.web_title))
                .setContentText(getString(R.string.web_description))
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setOngoing(true);
        notificationManager.notify(ID, not.build());
        startForeground(ID, not.build());
        return new Binder();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = KlipperApp.INSTANCE.getSharedPreferences("web", 0);
        try {
            httpServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        httpServer.stop();

        stopForeground(true);
        notificationManager.cancel(ID);
    }

    private static class HttpServer extends NanoWSD {

        public HttpServer() {
            super(PORT);
        }

        private Response serveStatic(String path) {
            Context ctx = KlipperApp.INSTANCE;
            if (path.equals("/")) path = "/index.html";

            try {
                String mimeType = "text/plain";
                if (path.endsWith(".js")) {
                    mimeType = "text/javascript";
                } else if (path.endsWith(".html")) {
                    mimeType = "text/html";
                } else if (path.endsWith(".css")) {
                    mimeType = "text/css";
                }
                InputStream in = ctx.getAssets().open((Prefs.isMainsailEnabled() ? "mainsail" : "fluidd") + path);
                Response response = newChunkedResponse(Status.OK, mimeType, in);
                response.addHeader("Date", dateFormat.format(new Date()));
                response.addHeader("Last-Modified", getLastModifiedString());
                response.addHeader("Cache-Control", "max-age=604800");
                return response;
            } catch (IOException e) {
                if (Prefs.isMainsailEnabled()) {
                    return serveStatic("/index.html");
                }
                return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not Found");
            }
        }

        private String getLastModifiedString() {
            long lastModified = mPrefs.getLong("last_modified", System.currentTimeMillis());
            return dateFormat.format(new Date(lastModified));
        }

        @Override
        public Response serve(IHTTPSession session) {
            Matcher m = API_PATTERN.matcher(session.getUri());
            if (m.find()) {
                try {
                    HttpURLConnection con = (HttpURLConnection) new URL("http://127.0.0.1:7125/" + session.getUri().substring(1)).openConnection();
                    con.setRequestMethod(session.getMethod().name());
                    if (session.getMethod() == Method.POST || session.getMethod() == Method.PUT || session.getMethod() == Method.PATCH) {
                        for (Map.Entry<String, String> en : session.getHeaders().entrySet()) {
                            con.addRequestProperty(en.getKey(), en.getValue());
                        }

                        long len = Long.parseLong(session.getHeaders().get("content-length"));
                        InputStream in = session.getInputStream();
                        OutputStream out = con.getOutputStream();
                        byte[] buffer = new byte[10240]; int c;
                        int totalWritten = 0;
                        while (totalWritten < len && (c = in.read(buffer)) != -1) {
                            out.write(buffer, 0, c);
                            totalWritten += c;
                        }
                        out.close();
                    }
                    Response r = newChunkedResponse(Status.OK, con.getContentType(), con.getResponseCode() >= 200 && con.getResponseCode() < 300 ? con.getInputStream() : con.getErrorStream());
                    for (Map.Entry<String, List<String>> en : con.getHeaderFields().entrySet()) {
                        for (String val : en.getValue()) {
                            r.addHeader(en.getKey(), val);
                        }
                    }
                    r.setKeepAlive(false);
                    return r;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            if (session.getUri().startsWith("/index.html") || session.getUri().equals("/")) {
                return serveStatic("/");
            } else {
                return serveStatic(session.getUri());
            }
        }

        @Override
        protected WebSocket openWebSocket(IHTTPSession handshake) {
            try {
                AtomicReference<WebSocket> localRef = new AtomicReference<>();
                WebSocketClient remote = new WebSocketClient(new URI("ws://127.0.0.1:7125/websocket")) {
                    @Override
                    public void onOpen(ServerHandshake handshakedata) {}

                    @Override
                    public void onMessage(String message) {
                        if (!localRef.get().isOpen()) {
                            close();
                            return;
                        }
                        try {
                            localRef.get().send(message);
                        } catch (IOException e) {
                            onError(e);
                        }
                    }

                    @Override
                    public void onMessage(ByteBuffer bytes) {
                        try {
                            localRef.get().send(bytes.array());
                        } catch (IOException e) {
                            onError(e);
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        if (!remote) {
                            try {
                                localRef.get().close(CloseCode.NormalClosure, reason, false);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }

                    @Override
                    public void onError(Exception ex) {
                        Log.e("websocket_proxy", "Remote socket error", ex);
                    }
                };
                WebSocket local = new WebSocket(handshake) {
                    @Override
                    protected void onOpen() {
                        try {
                            remote.connectBlocking();
                        } catch (InterruptedException e) {
                            onException(new IOException(e));
                        }
                    }

                    @Override
                    protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {
                        if (!initiatedByRemote) {
                            remote.close();
                        }
                    }

                    @Override
                    protected void onMessage(WebSocketFrame message) {
                        if (!remote.isOpen()) {
                            try {
                                close(CloseCode.NormalClosure, "", false);
                            } catch (IOException e) {
                                onException(e);
                            }
                            return;
                        }
                        if (message.getOpCode() == OpCode.Text) {
                            remote.send(message.getTextPayload());
                        } else {
                            remote.send(message.getBinaryPayload());
                        }
                    }

                    @Override
                    protected void onPong(WebSocketFrame pong) {}

                    @Override
                    protected void onException(IOException exception) {
                        Log.e("websocket_proxy", "Local socket error", exception);
                    }
                };
                localRef.set(local);
                return local;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
