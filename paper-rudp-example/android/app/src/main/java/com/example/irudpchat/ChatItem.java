package com.example.irudpchat;

import android.content.Context;
import android.text.format.DateFormat;
import android.text.format.Formatter;

import java.io.File;
import java.util.Date;
import java.util.Locale;

/** One row of the conversation: a message, a file transfer or a note from the app. */
final class ChatItem {
    static final int IN = 0, OUT = 1, INFO = 2;
    static final int PENDING = 0, SENT = 1, FAILED = 2;       // for messages we sent

    final int kind;
    final String peer;
    final long time = System.currentTimeMillis();
    String text;
    int sendState = PENDING;
    // file transfers
    String key, name, mime, detail;
    long size, done, total;
    int transferState = -1;
    File file;

    private ChatItem(int kind, String peer, String text) {
        this.kind = kind;
        this.peer = peer;
        this.text = text;
    }

    static ChatItem incoming(String from, String text) {
        return new ChatItem(IN, from, text);
    }

    static ChatItem outgoing(String to, String text) {
        return new ChatItem(OUT, to, text);
    }

    static ChatItem info(String text) {
        return new ChatItem(INFO, null, text);
    }

    static ChatItem transfer(ChatClient.Transfer t) {
        ChatItem it = new ChatItem(t.outgoing ? OUT : IN, t.peer, null);
        it.key = t.key;
        it.update(t);
        return it;
    }

    void update(ChatClient.Transfer t) {
        name = t.name;
        mime = t.mime;
        size = t.size;
        done = t.done;
        total = t.total;
        transferState = t.state;
        detail = t.detail;
        file = t.file;
    }

    boolean isTransfer() {
        return key != null;
    }

    String meta(Context ctx) {
        String clock = DateFormat.getTimeFormat(ctx).format(new Date(time));
        if (kind == INFO) return clock;
        String who = kind == IN ? peer : ctx.getString(R.string.to_peer, peer);
        if (kind == OUT && !isTransfer()) {
            int state = sendState == PENDING ? R.string.state_sending : sendState == SENT ? R.string.state_sent : R.string.state_failed;
            return who + " · " + clock + " · " + ctx.getString(state);
        }
        return who + " · " + clock;
    }

    String body(Context ctx) {
        if (!isTransfer()) return text;
        StringBuilder sb = new StringBuilder(name).append(" (").append(Formatter.formatShortFileSize(ctx, size)).append(")\n");
        if (transferState == ChatClient.Transfer.RUNNING && total > 0) {
            sb.append(String.format(Locale.US, "%d%% · ", 100 * done / total));
        } else if (transferState == ChatClient.Transfer.FAILED) {
            sb.append(ctx.getString(R.string.transfer_failed)).append(": ");
        }
        sb.append(detail == null ? "" : detail);
        if (file != null) sb.append('\n').append(ctx.getString(R.string.tap_to_open));
        return sb.toString();
    }
}
