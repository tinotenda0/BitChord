/**
 * MD5, for the Subsonic token login marble's gateway and the party server's
 * Connect sign-in both check: md5(password + salt). Web Crypto has no MD5, so a
 * small implementation lives here (from Marble Music's lib/connect.ts). Not used
 * for anything else.
 */
export function md5(input: string): string {
  const bytes = new TextEncoder().encode(input);
  const words = new Array<number>(((bytes.length + 8) >> 6) * 16 + 16).fill(0);
  for (let i = 0; i < bytes.length; i++) words[i >> 2] = (words[i >> 2] ?? 0) | ((bytes[i] ?? 0) << ((i % 4) * 8));
  words[bytes.length >> 2] = (words[bytes.length >> 2] ?? 0) | (0x80 << ((bytes.length % 4) * 8));
  const bitLength = bytes.length * 8;
  const last = (((bytes.length + 8) >> 6) + 1) * 16;
  words[last - 2] = bitLength >>> 0;
  words[last - 1] = Math.floor(bitLength / 2 ** 32);

  const S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
  const K = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 2 ** 32) >>> 0);
  let a0 = 0x67452301;
  let b0 = 0xefcdab89;
  let c0 = 0x98badcfe;
  let d0 = 0x10325476;
  for (let chunk = 0; chunk < last; chunk += 16) {
    let a = a0;
    let b = b0;
    let c = c0;
    let d = d0;
    for (let i = 0; i < 64; i++) {
      let f: number;
      let g: number;
      if (i < 16) {
        f = (b & c) | (~b & d);
        g = i;
      } else if (i < 32) {
        f = (d & b) | (~d & c);
        g = (5 * i + 1) % 16;
      } else if (i < 48) {
        f = b ^ c ^ d;
        g = (3 * i + 5) % 16;
      } else {
        f = c ^ (b | ~d);
        g = (7 * i) % 16;
      }
      const sum = (a + f + (K[i] ?? 0) + ((words[chunk + g] ?? 0) | 0)) >>> 0;
      const shift = (S[(i >> 4) * 4 + (i % 4)] ?? 0);
      a = d;
      d = c;
      c = b;
      b = (b + ((sum << shift) | (sum >>> (32 - shift)))) >>> 0;
    }
    a0 = (a0 + a) >>> 0;
    b0 = (b0 + b) >>> 0;
    c0 = (c0 + c) >>> 0;
    d0 = (d0 + d) >>> 0;
  }
  return [a0, b0, c0, d0]
    .map((w) => Array.from({ length: 4 }, (_, i) => ((w >>> (i * 8)) & 0xff).toString(16).padStart(2, "0")).join(""))
    .join("");
}
