'use strict';
// File storage of the relay (paper, Sec. 4.6): files are kept by their SHA-1. A partial file grows in
// place (each segment is written at its offset); its block bitmap is saved after every completed block,
// so an interrupted upload resumes even after a restart. Complete files are renamed to <sha1>.bin.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const S = require('./irudp/segment');

function sha1OfFile(file) {
  const h = crypto.createHash('sha1'); const fd = fs.openSync(file, 'r'); const buf = Buffer.alloc(1 << 20);
  try { let n; while ((n = fs.readSync(fd, buf, 0, buf.length, null)) > 0) h.update(buf.subarray(0, n)); } finally { fs.closeSync(fd); }
  return h.digest('hex');
}

class FileStore {
  constructor(dir) { this.dir = dir; fs.mkdirSync(dir, { recursive: true }); }

  paths(sha1) {
    const base = path.join(this.dir, sha1);
    return { part: `${base}.part`, map: `${base}.map`, meta: `${base}.json`, bin: `${base}.bin` };
  }

  hasComplete(sha1) { return fs.existsSync(this.paths(sha1).bin); }

  meta(sha1) { try { return JSON.parse(fs.readFileSync(this.paths(sha1).meta, 'utf8')); } catch (e) { return null; } }

  openReceive(info) {                                  // info: { name, type, size, sha1, from, to }
    const p = this.paths(info.sha1); const nBlocks = S.blocksFor(info.size);
    if (this.hasComplete(info.sha1)) {                 // already stored: every block is held, nothing is sent
      const held = new Uint8Array(S.bitmapBytes(nBlocks)).fill(0xff);
      return { complete: true, held, writeSegment() {}, markBlock() {}, verify: () => true, finalize() {}, close() {}, discard() {} };
    }
    // the block map is trusted only next to the partial file it describes
    const resumable = fs.existsSync(p.part) && fs.statSync(p.part).size === info.size;
    if (!resumable) {
      const fd = fs.openSync(p.part, 'w'); fs.ftruncateSync(fd, info.size); fs.closeSync(fd);
      try { fs.unlinkSync(p.map); } catch (e) { /* none */ }
    }
    fs.writeFileSync(p.meta, JSON.stringify({ ...info, complete: false }, null, 1));
    let held = new Uint8Array(S.bitmapBytes(nBlocks));
    if (resumable) {
      try { const m = fs.readFileSync(p.map); if (m.length === held.length) held = new Uint8Array(m); } catch (e) { /* no map yet */ }
    }
    const fd = fs.openSync(p.part, 'r+');
    let open = true;
    return {
      complete: false, held,
      writeSegment: (seq, buf) => fs.writeSync(fd, buf, 0, buf.length, seq * S.MSS),
      markBlock: (bitmap) => fs.writeFileSync(p.map, Buffer.from(bitmap)),
      // another session (a second user sending the same file) may have finished it first
      verify: () => { fs.fsyncSync(fd); return sha1OfFile(fs.existsSync(p.part) ? p.part : p.bin) === info.sha1; },
      finalize: () => {
        if (open) { fs.closeSync(fd); open = false; }
        if (fs.existsSync(p.part)) fs.renameSync(p.part, p.bin);
        try { fs.unlinkSync(p.map); } catch (e) { /* none */ }
        fs.writeFileSync(p.meta, JSON.stringify({ ...info, complete: true }, null, 1));
      },
      close: () => { if (open) { fs.closeSync(fd); open = false; } },
      // a file that failed its hash check must not be resumed (its block map says every block is held),
      // so the next attempt starts again from the beginning
      discard: () => {
        if (open) { fs.closeSync(fd); open = false; }
        for (const f of [p.part, p.map]) { try { fs.unlinkSync(f); } catch (e) { /* already gone */ } }
      },
    };
  }

  openSend(sha1) {
    const p = this.paths(sha1); const size = fs.statSync(p.bin).size; const fd = fs.openSync(p.bin, 'r');
    return {
      size,
      readSegment: (seq) => {
        const len = Math.min(S.MSS, size - seq * S.MSS); const b = Buffer.alloc(len);
        fs.readSync(fd, b, 0, len, seq * S.MSS);
        return b;
      },
      close: () => fs.closeSync(fd),
    };
  }
}

module.exports = { FileStore, sha1OfFile };
