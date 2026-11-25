package com.example.irudpchat;

import android.app.Application;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.example.irudpchat.irudp.LossClassifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the connection and the conversation, so that they survive the activity being rebuilt (rotation,
 * dark mode, a new font size). ChatClient calls arrive on background threads and are moved to the main
 * thread here; all other methods are called on the main thread.
 */
public class ChatViewModel extends AndroidViewModel {
    /** The screen that shows the conversation, attached while an activity exists. */
    interface Screen {
        void itemAdded(int position);

        void itemChanged(int position);

        void statusChanged(String text);
    }

    private static final AtomicBoolean CACHE_CLEANED = new AtomicBoolean();

    final List<ChatItem> items = new ArrayList<>();
    private final Application app;
    private final HashMap<Long, ChatItem> unconfirmed = new HashMap<>();   // our messages waiting for the relay
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LossClassifier classifier;
    private ChatClient client;
    private int generation;                         // which client is the current one
    private String status;
    private Screen screen;

    public ChatViewModel(@NonNull Application application) {
        super(application);
        app = application;
        LossClassifier loaded = null;
        try (InputStream in = application.getAssets().open("gbdt.txt")) {
            loaded = LossClassifier.load(in);
        } catch (IOException e) {
            items.add(ChatItem.info(application.getString(R.string.no_model)));
        }
        classifier = loaded;
        if (CACHE_CLEANED.compareAndSet(false, true)) {   // once per process: copies left when the app was killed while sending
            io.execute(() -> {
                File[] old = new File(application.getCacheDir(), "outgoing").listFiles();
                if (old != null) for (File f : old) if (!f.delete()) f.deleteOnExit();
            });
        }
    }

    void attach(Screen s) {
        screen = s;
    }

    void detach(Screen s) {
        if (screen == s) screen = null;
    }

    String status() {
        return status;
    }

    boolean isConnected() {
        return client != null;
    }

    void connect(String host, int port, String user) {
        if (client != null) client.stop();
        final int gen = ++generation;
        File downloads = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) downloads = new File(app.getFilesDir(), "downloads");
        client = new ChatClient(host, port, user, downloads, classifier, new ChatClient.Listener() {
            // called on background threads
            @Override
            public void onStatus(String text) {
                main.post(() -> {
                    if (gen == generation) setStatus(text);   // a client that was replaced has nothing to say
                });
            }

            @Override
            public void onMessage(String from, String text) {
                main.post(() -> add(ChatItem.incoming(from, text)));
            }

            @Override
            public void onMessageResult(long localId, boolean accepted) {
                main.post(() -> messageResult(localId, accepted));
            }

            @Override
            public void onTransfer(ChatClient.Transfer t) {
                main.post(() -> transfer(t));
            }
        });
        setStatus(app.getString(R.string.connecting, host + ":" + port));
        client.start();
    }

    void sendText(String to, String text) {
        if (client == null) return;
        ChatItem item = ChatItem.outgoing(to, text);
        add(item);
        unconfirmed.put(client.sendMessage(to, text), item);
    }

    /** Copies the chosen file into the cache (a content URI may not allow seeking), then sends the copy. */
    void sendFile(String to, Uri uri) {
        if (client == null) return;
        try {
            io.execute(() -> copyAndSend(to, uri));
        } catch (RejectedExecutionException e) {
            // the model is being cleared
        }
    }

    private void copyAndSend(String to, Uri uri) {
        final String name = displayName(uri);
        File copy = null;
        try {
            final String mime = app.getContentResolver().getType(uri);
            File dir = new File(app.getCacheDir(), "outgoing");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            copy = File.createTempFile("send", ".bin", dir);
            try (InputStream in = app.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(copy)) {
                if (in == null) throw new IOException("cannot open the file");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            final File ready = copy;
            main.post(() -> {                          // through the client that is current now
                if (client != null) client.sendFile(to, ready, name, mime, true);   // it deletes the copy when done
                else if (!ready.delete()) ready.deleteOnExit();
            });
        } catch (IOException | RuntimeException e) {
            if (copy != null && !copy.delete()) copy.deleteOnExit();
            final String why = String.valueOf(e.getMessage());
            main.post(() -> add(ChatItem.info(app.getString(R.string.cannot_read, name, why))));
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = app.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (RuntimeException e) {
            // fall back to the last part of the URI
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "file";
    }

    private void messageResult(long localId, boolean accepted) {
        ChatItem item = unconfirmed.remove(localId);
        if (item == null) return;
        item.sendState = accepted ? ChatItem.SENT : ChatItem.FAILED;
        changed(item);
    }

    private void transfer(ChatClient.Transfer t) {
        ChatItem item = findTransfer(t.key);
        if (item == null) {
            add(ChatItem.transfer(t));
        } else {
            item.update(t);
            changed(item);
        }
    }

    private void add(ChatItem item) {
        items.add(item);
        if (screen != null) screen.itemAdded(items.size() - 1);
    }

    private void changed(ChatItem item) {
        int i = items.lastIndexOf(item);
        if (i >= 0 && screen != null) screen.itemChanged(i);
    }

    private ChatItem findTransfer(String key) {
        for (int i = items.size() - 1; i >= 0; i--) if (key.equals(items.get(i).key)) return items.get(i);
        return null;
    }

    private void setStatus(String text) {
        status = text;
        if (screen != null) screen.statusChanged(text);
    }

    @Override
    protected void onCleared() {
        if (client != null) client.stop();
        client = null;
        io.shutdownNow();
    }
}
