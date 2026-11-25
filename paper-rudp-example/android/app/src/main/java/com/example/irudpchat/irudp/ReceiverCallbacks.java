package com.example.irudpchat.irudp;

/** The owner of a receiving session: stores the file's bytes, checks the result and hears about the end. */
public interface ReceiverCallbacks {
    /** Writes len bytes of src (from off) at file offset seq x 1384; throws an unchecked exception on I/O errors. */
    void write(int seq, byte[] src, int off, int len);

    /** Block b (256 segments) is complete; heldBitmap is the bitmap of complete blocks, to save for resuming. */
    void onBlock(int b, byte[] heldBitmap);

    /** The whole file is there: compare its SHA-1 with the offered one. */
    boolean verify();

    void onProgress(long doneSegments, long totalSegments);

    /** Called once. */
    void onDone(boolean ok, String reason);
}
