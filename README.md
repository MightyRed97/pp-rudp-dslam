# Network papers

Two papers on network engineering, and a working implementation of the first one.

| Folder | What it holds |
|---|---|
| [paper-rudp](paper-rudp/) | *A Study of Performance Improvement of a File Transfer System Based on an Improved RUDP Protocol Data Structure in a Chat Application Using the SIP Protocol*: the PDF, its Markdown source (`paper.md`), the figures and the references. |
| [paper-rudp-example](paper-rudp-example/) | A working implementation of that paper. It has a Node.js relay server for SIP-style chat and for file transfer over the improved RUDP (IRUDP), with tests and a benchmark, and an Android chat app in Java. Its README explains how to run both. |
| [paper-dslam](paper-dslam/) | *A Study on the Design and Implementation of a VDSL2 DSLAM Based on the WinPath3 Network Processor with Learning-Assisted Line Management*: the PDF, its Markdown source, the figures and the references. |

Both papers are teaching examples: their authors, institutions, datasets and results are fictional, as the note on their first page says. The example code is real and runs.

Each `references` folder holds the cited works that may be redistributed. For each of the others, it holds a text file with the full citation, a link to the work and the reason its PDF is not included.
