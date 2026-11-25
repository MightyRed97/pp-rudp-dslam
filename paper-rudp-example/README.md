# IRUDP Chat: an example of the paper's file transfer system

This is a working example of the system described in the paper *A Study of Performance Improvement of a File Transfer System Based on an Improved RUDP Protocol Data Structure in a Chat Application Using the SIP Protocol* (folder [`paper-rudp`](../paper-rudp/)). The paper is a teaching example: its authors, institutions, dataset and results are fictional. This code is real and runs. It has two parts:

- **`server/`**: the relay of Fig. 1, in Node.js with no dependencies. It is a SIP registrar and message relay, plus the IRUDP transport of Section 4: it receives uploads, stores files by their SHA-1 and delivers them to the recipient, or later if the recipient is offline. The folder also has a command-line chat client, the tests and a small benchmark.
- **`android/`**: the chat app for Android, in Java (an Android Studio project). It sends and receives chat messages and files, shows progress, and resumes interrupted transfers.

Both parts use the same protocol and the same loss classifier model (`server/model/gbdt.txt`, copied to `android/app/src/main/assets/gbdt.txt`).

## Run the server

You need Node.js 18 or later; there is nothing to install.

```
cd server
node src/main.js
```

The relay listens on UDP port 5070 (SIP) and UDP port 5080 (IRUDP), and keeps its files and its queue of undelivered messages in `server/data/`. Options:

| option | default | meaning |
|---|---|---|
| `--sip-port` | 5070 | SIP port |
| `--irudp-port` | 5080 | IRUDP port |
| `--public-ip` | first non-loopback IPv4 address | address written in the SDP answers |
| `--advertise-port` | the IRUDP port | IRUDP port given to the clients, if a port forward changes it |
| `--data` | `server/data` | folder for stored files and the queue |

## Chat from the command line

Start two clients in two more terminals:

```
node client/cli.js --user bob
node client/cli.js --user alice
```

Type `/to bob` to choose the recipient, then any line to send it as a message. `/file <path>` sends a file and `/quit` exits. Received files are saved in `downloads-<user>/`. To reach a relay on another computer, add `--server <host>` and `--port <n>`.

## Build and run the Android app

1. In Android Studio, choose File > Open and select the `android` folder. Android Studio downloads Gradle 8.9 and the Android Gradle plugin 8.7.3 by itself. From a terminal, `android\gradlew.bat assembleDebug` (or `./gradlew` on Linux and macOS) builds the APK, given a JDK 17 and the Android SDK.
2. Start the relay on your computer, then run the app on an emulator. An emulator reaches the computer it runs on at 10.0.2.2, so the default server, `10.0.2.2:5070`, works as it is.
3. Enter your name (for example `alice`) and press **Connect**. The status line then shows "registered as alice". Enter the recipient (for example `bob`), then type a message, or press **File** to choose a file.

On a real phone, enter the computer's address on your local network, as printed by the server. You also need to allow UDP ports 5070 and 5080 through the computer's firewall.

A simple test setup uses one computer: run the relay, run `node client/cli.js --user bob`, and run the app as alice in the emulator. Messages and files then go both ways. Files received by the app appear in the chat, where you tap one to open it. They are stored in the app's folder `Android/data/com.example.irudpchat/files/Download/`.

The app was tested on an Android 15 (API 35) emulator against the relay, with a lossy link of 2% random loss in front of the relay's IRUDP port:
- registration through the emulator's NAT, and chat in both directions, including non-Latin text;
- files in both directions, from 2 MB to 40 MB, with SHA-1 checked on the phone and at the other end; sending goes through the system file picker;
- a 6 s loss of network (airplane mode) during a download, which the transfer rides through;
- a 45 s loss of network, after which the relay offers the file again and the download resumes from the blocks already on the phone;
- rotation and dark mode, with the conversation and the connection kept;
- opening a received image.

## Running without internet

The [`offline/`](offline/) folder holds a kit for a Windows computer without internet access: a JDK, Gradle, Node.js, every library the build uses, and the app ready to install. With it you can start the relay (`offline\run-server.bat`), install the app (`offline\install-app.bat`), and build and test the app (`offline\build-app.bat`). The large parts are not in the repository: download them from the repository's release, or make them with `offline\tools\make-kit.ps1`. See [`offline/README.md`](offline/README.md).

## Tests

```
cd server
npm test          # unit tests, then the end-to-end test
npm run bench     # the classifier on and off (under a minute)
```

- **Unit tests** (`test/unit-test.js`):
  - The classifier reproduces the Python reference model on 40 held-out vectors.
  - The timing wheel fires every timer once and at the right tick, including after a 5 s stall.
  - In-memory transfers succeed without loss, with 5% loss, and through a delay spike longer than the RTO.
  - The ACK bitmap matches the example in the paper.
  - The wire format, the discarding of a file that fails its hash check, file-name sanitising, and session closing all behave as specified.
- **End-to-end test** (`test/e2e-test.js`) starts a relay and two clients, alice and bob. All IRUDP traffic passes through `test/lossy-proxy.js`, which emulates a 20 Mbit/s link with a 200-packet queue, 25 ms delay each way and 3% random loss in bursts of 2. The test checks four things:
  - A chat message is delivered.
  - A 6 MB file arrives through a 3 s outage with a matching SHA-1.
  - An upload killed halfway resumes from the block map and sends only the missing segments.
  - A message and a file sent while bob is offline are delivered when he registers again.

The **Android JUnit tests** are in `android/app/src/test`. Run them in Android Studio (right-click the `test` folder, then Run) or with `gradlew test`. They check that:
- the Java code builds the same packets as the server, byte for byte, against vectors written by `server/test/unit-test.js`;
- the classifier gives the probabilities of the Python model to within 1e-12;
- the timing wheel and the in-memory transfers behave as in the Node tests;
- the SIP and SDP texts of the relay are read correctly.

## The benchmark

`npm run bench` uploads the same 2 MB file through the emulated link, once with the classifier and once without it. Without the classifier, every loss event reduces the window, as plain CUBIC does. One run on the development computer gave:

| random loss | with classifier: Mbit/s | reductions | without classifier: Mbit/s | reductions |
|---|---|---|---|---|
| 0% | 11.2 | 0 | 11.2 | 0 |
| 1% | 13.2 | 2 | 4.2 | 9 |
| 3% | 12.5 | 2 | 2.8 | 15 |
| 5% | 11.8 | 1 | 1.3 | 38 |

These numbers vary from run to run. The emulated link runs inside one Node process, with timers of about 1 ms (about 15 ms on Windows), so they cannot be compared with the paper's results, which come from the simulator of Section 5. The pattern is the one of Fig. 4(a): with the classifier, random losses no longer cut the window.

## Protocol

### Signalling (Fig. 2)

```
alice (app)                   relay                          bob
REGISTER  ------------------> 200 OK                <-------- REGISTER
MESSAGE   ------------------> 202 Accepted
                              MESSAGE ------------------->   200 OK
INVITE (SDP offer)  --------> 200 OK (SDP answer: IRUDP port of the relay)
ACK       ------------------>
IRUDP: SYN ------------------> SYNACK (blocks the relay already holds)
       DATA ...   <--  ACK ... (cumulative ACK + bitmap words)
       FIN  ------------------> FINACK (SHA-1 checked)
BYE       ------------------> 200 OK
                              INVITE (SDP offer) -------->   200 OK (SDP answer)
                              <---------------------------   ACK
                              <---------------------------   IRUDP SYN (blocks bob holds)
                              SYNACK, DATA ... FIN ------>   FINACK
```

A file offer uses the SDP attributes of RFC 5547, for example:

```
m=application 5080 UDP/IRUDP *
a=setup:passive
a=sendonly
a=file-selector:name:"site photo 1.jpg" type:image/jpeg size:4194304 hash:sha-1:0A:1B:2C:...:67
a=file-transfer-id:f3a9c2d18b7e6054
a=irudp:mss=1384;ring=1024;block=256
```

The client always opens the IRUDP session (it sends the SYN) in both directions. This is why the app works behind NAT and in the emulator. The relay finds its users by the source address of their REGISTER, not by the Contact header.

### IRUDP segments (Section 4.2, Fig. 3a)

Every segment starts with a 16-byte header; all fields are big-endian.

| bytes | DATA | ACK |
|---|---|---|
| 0 | version (2 bits), type (3 bits), flags (3 bits) | same |
| 1 | ACK frequency requested from the receiver (Eq. 7) | number of bitmap words k |
| 2–3 | receive window (segments) | same |
| 4–7 | connection ID | same |
| 8–11 | sequence number = file offset / 1384 | cumulative ACK (next expected segment) |
| 12–15 | send time (µs, wraps at 2³²) | echoed send time + ACK hold time |

- **Types:** DATA 0, ACK 1, SYN 2, SYNACK 3, FIN 4, FINACK 5, RST 6.
- **DATA payload:** 1,384 bytes, so each segment fits in a 1,400-byte UDP payload.
- **ACK payload:** k little-endian 64-bit words. Bit b of word w reports segment a + 1 + 64w + b, where a is the cumulative ACK.
- **SYN payload:** role (0 = client sends, 1 = client receives), block size in segments (256), file size (8 bytes), SHA-1 (20 bytes), transfer ID (16 bytes), and, when the client receives, the bitmap of the blocks it already holds.
- **SYNACK payload:** status, block size, file size, SHA-1, and the bitmap of the blocks the relay already holds.
- **FINACK payload:** 0 if the SHA-1 matched, 2 if it did not.

## Where the paper is in the code

| Paper | Node (`server/src`) | Java (`android/app/src/main/java/com/example/irudpchat`) |
|---|---|---|
| Sec. 4.2, Fig. 3(a): segment formats | `irudp/segment.js` | `irudp/Segment.java` |
| Sec. 4.3, Eqs. 4–6, Listing 1: ring, scoreboard, time-based loss marking, RTO | `irudp/sender.js` | `irudp/IrudpSender.java` |
| Sec. 4.4, Eq. 7, Listing 2: receiver bitmap, ACK words, ACK frequency | `irudp/receiver.js` | `irudp/IrudpReceiver.java` |
| Sec. 4.5, Listing 3: two-level timing wheel shared by all sessions | `irudp/wheel.js`, `irudp/endpoint.js` | `irudp/TimingWheel.java`, `irudp/IrudpEndpoint.java` |
| Sec. 4.6: block map and resumable transfer | `storage.js`, held blocks in `irudp/sender.js` | `ChatClient.download`, held blocks in `IrudpSender` |
| Sec. 4.7, Eqs. 8–11, Listing 4, Algorithm 1: CUBIC and the gated window reduction | `irudp/sender.js` (`onLoss`, `grow`), `irudp/classifier.js` | `irudp/IrudpSender.java`, `irudp/LossClassifier.java` |
| Fig. 1 and Fig. 2: relay, registrar and signalling | `relay.js`, `sip.js` | `ChatClient.java`, `Sip.java`, `Sdp.java` |

## Differences from the paper

- **Integrity check.** The example checks the SHA-1 of the whole file, taken from the SDP offer (RFC 5547), when the transfer ends. It leaves out the per-block SHA-256 manifest of Section 4.6. If the check fails, the partial file is deleted and the next attempt starts again from the beginning.
- **No security.** There is no authentication and no encryption, and anyone can register under any name. The relay stores files as they are. Use the example on a test network only.
- **Small SIP subset.** The example uses only REGISTER, MESSAGE, INVITE, ACK and BYE, over UDP only, with no proxy routing and no digest authentication. It is not meant to work with other SIP software.
- **Android limits.**
  - Transfers run only while the app is open; there is no foreground service. Android freezes an app in the background, so messages and files sent to it meanwhile wait at the relay, which delivers them when the app registers again.
  - A chosen file is first copied to the app's cache, because some content providers do not allow random access.
  - Received files stay in the app's own folder.
- **Relay state.** The relay keeps the online users and the offers in progress in memory. The stored files and the queue of undelivered messages and files survive a restart.

## Notes from testing

- **The link emulator must keep packets in order.** An early version of the test proxy gave each packet its own timer. Node runs timers grouped by their delay, so on Windows, where timers fire about every 15 ms, later packets overtook earlier ones. They overtook them by more than the reordering window of Eq. (5), and about a quarter of all segments were resent for nothing. The proxy now releases packets from a FIFO queue.
- **The probe can resend a segment that was only late.** After a delay spike longer than the RTO, the probe resends a segment whose first copy is still on its way. When that first copy is acknowledged, the resend time must not become the RACK time, or every segment in flight is marked lost. The sender applies the rule of RFC 8985, Section 6.2, using the echoed send time. A unit test covers this case in both the Node and the Java code.
- **A second offer of a file means the first session is dead.** The relay offers a file again only after its last delivery to that user has ended. A client still receiving that file is waiting for its 60 s idle timeout, so it ends that session at once and starts the new one, which resumes from the blocks it already holds. On the emulator, after a 45 s loss of network, this skipped 16 to 27 complete blocks instead of sending the whole file again.
