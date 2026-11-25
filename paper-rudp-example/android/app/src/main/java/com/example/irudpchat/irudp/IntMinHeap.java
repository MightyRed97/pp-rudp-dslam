package com.example.irudpchat.irudp;

import java.util.Arrays;

/** A binary min-heap of ints: the retransmission queue of the sender, lowest sequence number first. */
final class IntMinHeap {
    private int[] a = new int[64];
    private int n;

    int size() {
        return n;
    }

    int peek() {
        return a[0];
    }

    void push(int v) {
        if (n == a.length) a = Arrays.copyOf(a, 2 * n);
        int i = n++;
        a[i] = v;
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (a[p] <= a[i]) break;
            int t = a[p];
            a[p] = a[i];
            a[i] = t;
            i = p;
        }
    }

    int pop() {
        int top = a[0];
        int last = a[--n];
        if (n > 0) {
            a[0] = last;
            int i = 0;
            for (;;) {
                int l = 2 * i + 1, r = l + 1, m = i;
                if (l < n && a[l] < a[m]) m = l;
                if (r < n && a[r] < a[m]) m = r;
                if (m == i) break;
                int t = a[m];
                a[m] = a[i];
                a[i] = t;
                i = m;
            }
        }
        return top;
    }
}
