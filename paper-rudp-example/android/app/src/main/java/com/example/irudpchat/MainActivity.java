package com.example.irudpchat;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The chat screen: connect to the relay as a user, send messages and files to another user, and see
 * the messages and files that arrive. The connection and the conversation live in ChatViewModel, so
 * they survive this activity being rebuilt.
 */
public class MainActivity extends AppCompatActivity implements ChatViewModel.Screen {
    private static final String PREFS = "chat";

    private ChatViewModel model;
    private ChatAdapter adapter;
    private RecyclerView list;
    private EditText server, user, peer, input;
    private TextView status;
    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onFilePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {   // keep clear of the system bars and the keyboard
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!night);
        controller.setAppearanceLightNavigationBars(!night);

        server = findViewById(R.id.server);
        user = findViewById(R.id.user);
        peer = findViewById(R.id.peer);
        input = findViewById(R.id.input);
        status = findViewById(R.id.status);
        if (savedInstanceState == null) {                 // after a rebuild the fields keep what was typed
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            server.setText(prefs.getString("server", "10.0.2.2:5070"));   // the emulator reaches the computer at 10.0.2.2
            user.setText(prefs.getString("user", "alice"));
            peer.setText(prefs.getString("peer", "bob"));
        }

        model = new ViewModelProvider(this).get(ChatViewModel.class);
        adapter = new ChatAdapter(model.items, this::openFile);
        list = findViewById(R.id.messages);
        LinearLayoutManager layout = new LinearLayoutManager(this);
        layout.setStackFromEnd(true);
        list.setLayoutManager(layout);
        list.setAdapter(adapter);
        model.attach(this);
        String now = model.status();
        status.setText(now != null ? now : getString(R.string.not_connected));

        findViewById(R.id.connect).setOnClickListener(v -> connect());
        findViewById(R.id.send).setOnClickListener(v -> sendText());
        findViewById(R.id.attach).setOnClickListener(v -> {
            if (!model.isConnected()) toast(R.string.connect_first);
            else if (recipient() != null) picker.launch(new String[] {"*/*"});
        });
    }

    @Override
    protected void onDestroy() {
        model.detach(this);
        super.onDestroy();
    }

    private void connect() {
        String address = server.getText().toString().trim();
        String me = user.getText().toString().trim();
        String host = address;
        int port = 5070;
        int colon = address.lastIndexOf(':');
        if (colon >= 0) {
            host = address.substring(0, colon);
            try {
                port = Integer.parseInt(address.substring(colon + 1));
            } catch (NumberFormatException e) {
                port = -1;
            }
        }
        if (host.isEmpty() || port < 1 || port > 65535 || !me.matches(ChatClient.NAME_PATTERN)) {
            toast(R.string.bad_settings);
            return;
        }
        savePrefs();
        model.connect(host, port, me);
    }

    /** The recipient typed in the To field, or null (with a message) if it is not a valid name. */
    private String recipient() {
        String to = peer.getText().toString().trim();
        if (to.matches(ChatClient.NAME_PATTERN)) return to;
        toast(R.string.no_peer);
        return null;
    }

    private void sendText() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!model.isConnected()) {
            toast(R.string.connect_first);
            return;
        }
        String to = recipient();
        if (to == null) return;
        input.setText("");
        savePrefs();
        model.sendText(to, text);
    }

    private void onFilePicked(Uri uri) {
        if (uri == null || !model.isConnected()) return;
        String to = recipient();
        if (to == null) return;
        savePrefs();
        model.sendFile(to, uri);
    }

    private void openFile(ChatItem item) {
        if (item.file == null || !item.file.exists()) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", item.file);
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, item.name));
        } catch (IllegalArgumentException | ActivityNotFoundException e) {
            toast(R.string.no_viewer);
        }
    }

    private void savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("server", server.getText().toString().trim())
                .putString("user", user.getText().toString().trim())
                .putString("peer", peer.getText().toString().trim())
                .apply();
    }

    private void toast(int text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ---- ChatViewModel.Screen: called on the main thread ------------------------------------------------

    @Override
    public void itemAdded(int position) {
        adapter.notifyItemInserted(position);
        list.scrollToPosition(position);
    }

    @Override
    public void itemChanged(int position) {
        adapter.notifyItemChanged(position);
    }

    @Override
    public void statusChanged(String text) {
        status.setText(text);
    }
}
