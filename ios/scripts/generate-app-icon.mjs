// Render Mirage's existing Android vector identity as an opaque iOS app icon.
// Geometry and colors come from android/app/src/main/res/drawable/ic_launcher_*.
// Uses only Node's standard library; no model-generated branding or remote assets.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const output = resolve(dirname(fileURLToPath(import.meta.url)), '../MirageApp/Assets.xcassets/AppIcon.appiconset');
const size = 1024;
const pin = [[54, 28]];
function cubic(a, b, c, d) {
  for (let step = 1; step <= 40; step++) {
    const t = step / 40, u = 1 - t;
    pin.push([0, 1].map(i => u ** 3 * a[i] + 3 * u * u * t * b[i] + 3 * u * t * t * c[i] + t ** 3 * d[i]));
  }
}
cubic([54, 28], [42.4, 28], [33, 37.4], [33, 49]);
cubic([33, 49], [33, 63], [54, 82], [54, 82]);
cubic([54, 82], [54, 82], [75, 63], [75, 49]);
cubic([75, 49], [75, 37.4], [65.6, 28], [54, 28]);
const triangle = [[49, 40], [49, 58], [66, 49]];
function contains(points, x, y) {
  let inside = false;
  for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
    const [ax, ay] = points[i], [bx, by] = points[j];
    if ((ay > y) !== (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside;
  }
  return inside;
}
const pixels = Buffer.alloc(size * (size * 3 + 1));
for (let row = 0; row < size; row++) {
  for (let col = 0; col < size; col++) {
    const sums = [0, 0, 0];
    for (const dy of [0.25, 0.75]) for (const dx of [0.25, 0.75]) {
      const x = (col + dx) * 108 / size, y = (row + dy) * 108 / size;
      const t = Math.max(0, Math.min(1, (x + y - 12) / 192));
      const highlight = 0.2 * Math.max(0, 1 - Math.hypot(x - 38, y - 34) / 70);
      const white = x >= 33 && x <= 75 && y >= 28 && y <= 82 && contains(pin, x, y) && !contains(triangle, x, y);
      for (let channel = 0; channel < 3; channel++) {
        const base = [99, 102, 241][channel] * (1 - t) + [67, 56, 202][channel] * t;
        sums[channel] += white ? 255 : base * (1 - highlight) + 255 * highlight;
      }
    }
    for (let channel = 0; channel < 3; channel++) pixels[row * (size * 3 + 1) + 1 + col * 3 + channel] = Math.round(sums[channel] / 4);
  }
}
function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(kind, data) {
  const body = Buffer.concat([Buffer.from(kind), data]);
  const length = Buffer.alloc(4), crc = Buffer.alloc(4);
  length.writeUInt32BE(data.length); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}
const header = Buffer.alloc(13);
header.writeUInt32BE(size, 0); header.writeUInt32BE(size, 4);
header[8] = 8; header[9] = 2; // RGB, no transparency; iOS supplies its own mask.
const png = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))]);
mkdirSync(output, { recursive: true });
writeFileSync(resolve(output, 'AppIcon.png'), png);
writeFileSync(resolve(output, 'Contents.json'), JSON.stringify({ images: [{ filename: 'AppIcon.png', idiom: 'universal', platform: 'ios', size: '1024x1024' }], info: { author: 'xcode', version: 1 } }, null, 2) + '\n');
writeFileSync(resolve(output, '../Contents.json'), JSON.stringify({ info: { author: 'xcode', version: 1 } }, null, 2) + '\n');
console.log(`Rendered opaque ${size}x${size} Mirage app icon (${png.length} bytes).`);
