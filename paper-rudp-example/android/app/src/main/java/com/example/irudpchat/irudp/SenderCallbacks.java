package com.example.irudpchat.irudp;

/** The owner of a sending session: supplies the file's bytes and hears about progress and the end. */
public interface SenderCallbacks {
    /** Copies segment seq (len bytes from offset seq x 1384) into dst at off; throws an unchecked exception on I/O errors. */
    void read(int seq, byte[] dst, int off, int len);

    void onProgress(long doneSegments, long totalSegments);

    /** Called once. stats is null if the session ended before it started sending. */
    void onDone(boolean ok, String reason, IrudpSender.Stats stats);
}
