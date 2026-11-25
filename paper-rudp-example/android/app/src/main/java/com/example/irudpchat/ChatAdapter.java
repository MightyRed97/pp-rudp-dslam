package com.example.irudpchat;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Shows the conversation held by ChatViewModel: our messages on the right, the others' on the left,
 * notes in the middle. The model changes the list and tells the activity, which notifies this adapter.
 */
final class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {
    interface OnOpen {
        void open(ChatItem item);
    }

    private final List<ChatItem> items;
    private final OnOpen onOpen;

    ChatAdapter(List<ChatItem> items, OnOpen onOpen) {
        this.items = items;
        this.onOpen = onOpen;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        final ChatItem it = items.get(position);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.bubble.getLayoutParams();
        lp.gravity = it.kind == ChatItem.OUT ? Gravity.END : it.kind == ChatItem.IN ? Gravity.START : Gravity.CENTER_HORIZONTAL;
        h.bubble.setLayoutParams(lp);
        h.bubble.setBackgroundResource(it.kind == ChatItem.OUT ? R.drawable.bubble_out
                : it.kind == ChatItem.IN ? R.drawable.bubble_in : R.drawable.bubble_info);
        h.meta.setText(it.meta(h.itemView.getContext()));
        h.body.setText(it.body(h.itemView.getContext()));
        boolean running = it.isTransfer() && it.transferState == ChatClient.Transfer.RUNNING;
        h.progress.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) h.progress.setProgress(it.total > 0 ? (int) (1000 * it.done / it.total) : 0);
        if (it.file != null) {
            h.itemView.setOnClickListener(v -> onOpen.open(it));
        } else {
            h.itemView.setOnClickListener(null);
            h.itemView.setClickable(false);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout bubble;
        final TextView meta, body;
        final ProgressBar progress;

        Holder(View v) {
            super(v);
            bubble = v.findViewById(R.id.bubble);
            meta = v.findViewById(R.id.meta);
            body = v.findViewById(R.id.body);
            progress = v.findViewById(R.id.progress);
        }
    }
}
