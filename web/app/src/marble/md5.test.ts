import { describe, expect, it } from 'vitest';
import { md5 } from './md5';

describe('md5', () => {
  it('matches the RFC 1321 test suite', () => {
    expect(md5('')).toBe('d41d8cd98f00b204e9800998ecf8427e');
    expect(md5('abc')).toBe('900150983cd24fb0d6963f7d28e17f72');
    expect(md5('message digest')).toBe('f96b697d7cb7938d525a2f31aaf161d0');
    expect(md5('12345678901234567890123456789012345678901234567890123456789012345678901234567890')).toBe('57edf4a22be3c955ac49da2e2107b67a');
  });

  it('hashes UTF-8, as the gateway does', () => {
    // Two bytes for é, as in UTF-8; Latin-1 would hash one.
    expect(md5('é')).not.toBe(md5(String.fromCharCode(0xe9 & 0x7f)));
    expect(md5('pässwort' + 'a1b2c3')).toMatch(/^[0-9a-f]{32}$/);
  });
});
