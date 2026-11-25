'use strict';
// Copies every artifact of a Gradle dependency cache (GRADLE_USER_HOME/caches/modules-2/files-2.1) into a
// repository with the Maven layout (group/as/folders/module/version/file), which a build can use without
// the internet (see ../README.md).
//
//   node gradle-cache-to-maven.js <GRADLE_USER_HOME> <repository folder>
const fs = require('fs');
const path = require('path');

const [home, out] = process.argv.slice(2);
if (!home || !out) {
  console.error('usage: node gradle-cache-to-maven.js <GRADLE_USER_HOME> <repository folder>');
  process.exit(2);
}
const cache = path.join(home, 'caches', 'modules-2', 'files-2.1');
const list = (dir) => fs.readdirSync(dir, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name);
let files = 0, bytes = 0, versions = 0;
for (const group of list(cache)) {
  for (const mod of list(path.join(cache, group))) {
    for (const ver of list(path.join(cache, group, mod))) {
      const dest = path.join(out, ...group.split('.'), mod, ver);
      fs.mkdirSync(dest, { recursive: true });
      versions++;
      for (const hash of list(path.join(cache, group, mod, ver))) {      // one folder per file, named by its SHA-1
        for (const file of fs.readdirSync(path.join(cache, group, mod, ver, hash))) {
          const src = path.join(cache, group, mod, ver, hash, file);
          fs.copyFileSync(src, path.join(dest, file));
          files++;
          bytes += fs.statSync(src).size;
        }
      }
    }
  }
}
console.log(`${versions} module versions, ${files} files, ${(bytes / 2 ** 20).toFixed(0)} MB -> ${out}`);
