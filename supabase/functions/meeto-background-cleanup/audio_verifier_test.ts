import { InvalidAudio, verifyAacLcM4a } from "./audio_verifier.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) throw new Error(`Expected ${expected}, received ${actual}`);
}
function assertInvalid(action: () => unknown): void {
  try { action(); } catch (error) { if (error instanceof InvalidAudio) return; throw error; }
  throw new Error("Expected invalid audio rejection");
}

function u32(value: number): Uint8Array {
  return new Uint8Array([(value >>> 24) & 255, (value >>> 16) & 255, (value >>> 8) & 255, value & 255]);
}
function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let offset = 0;
  for (const part of parts) { out.set(part, offset); offset += part.length; }
  return out;
}
function box(type: string, data: Uint8Array): Uint8Array {
  return concat(u32(data.length + 8), new TextEncoder().encode(type), data);
}
function descriptor(tag: number, payload: Uint8Array): Uint8Array {
  if (payload.length >= 128) throw new Error("test descriptor too long");
  return concat(new Uint8Array([tag, payload.length]), payload);
}
function fullBox(type: string, payload: Uint8Array): Uint8Array {
  return box(type, concat(new Uint8Array(4), payload));
}

function minimalM4a(): Uint8Array {
  const ftyp = box("ftyp", concat(new TextEncoder().encode("M4A "), u32(0), new TextEncoder().encode("M4A ")));
  const mvhd = fullBox("mvhd", concat(u32(0), u32(0), u32(1000), u32(1021)));
  const mdhd = fullBox("mdhd", concat(u32(0), u32(0), u32(44100), u32(45056)));
  const hdlr = fullBox("hdlr", concat(u32(0), new TextEncoder().encode("soun"), new Uint8Array(4)));
  const decoderSpecific = descriptor(5, new Uint8Array([0x10])); // AAC-LC AudioSpecificConfig
  const decoderConfig = descriptor(4, concat(new Uint8Array([0x40, 0x15, 0, 0, 0]), new Uint8Array(8), decoderSpecific));
  const es = descriptor(3, concat(new Uint8Array([0, 1, 0]), decoderConfig));
  const esds = fullBox("esds", es);
  const fixedSampleEntry = new Uint8Array(28);
  fixedSampleEntry[16] = 0; fixedSampleEntry[17] = 1; // mono
  fixedSampleEntry[24] = 0xac; fixedSampleEntry[25] = 0x44; // 44100 Hz, 16.16
  const mp4a = box("mp4a", concat(fixedSampleEntry, esds));
  const stsd = fullBox("stsd", concat(u32(1), mp4a));
  const stts = fullBox("stts", concat(u32(1), u32(44), u32(1024)));
  const stsz = fullBox("stsz", concat(u32(2), u32(44)));
  const stsc = fullBox("stsc", concat(u32(1), u32(1), u32(44), u32(1)));
  const makeMoov = (offset: number) => {
    const stco = fullBox("stco", concat(u32(1), u32(offset)));
    const stbl = box("stbl", concat(stsd, stts, stsz, stsc, stco));
    const minf = box("minf", stbl);
    const mdia = box("mdia", concat(mdhd, hdlr, minf));
    const trak = box("trak", mdia);
    return box("moov", concat(mvhd, trak));
  };
  const firstMoov = makeMoov(0);
  const mdat = box("mdat", new Uint8Array(128));
  const moov = makeMoov(ftyp.length + firstMoov.length + 8);
  return concat(ftyp, moov, mdat);
}

Deno.test("accepts a bounded AAC-LC M4A with consistent duration/sample tables", () => {
  assertEquals(verifyAacLcM4a(minimalM4a()).durationMs, 1022);
});

Deno.test("rejects an ftyp-only or MIME-disguised file", () => {
  assertInvalid(() => verifyAacLcM4a(box("ftyp", new TextEncoder().encode("M4A "))));
});

Deno.test("rejects audio above the server cap", () => {
  assertInvalid(() => verifyAacLcM4a(new Uint8Array(2 * 1024 * 1024 + 1)));
});
