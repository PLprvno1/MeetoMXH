const MAX_BYTES = 2 * 1024 * 1024;
const MAX_DURATION_MS = 120_000;

export class InvalidAudio extends Error {}
type Box = { type: string; start: number; data: number; end: number };

function u32(v: DataView, p: number): number { return v.getUint32(p, false); }
function ascii(bytes: Uint8Array, p: number, n: number): string {
  if (p < 0 || n < 0 || p + n > bytes.length) throw new InvalidAudio("truncated_box");
  return String.fromCharCode(...bytes.subarray(p, p + n));
}

function boxes(bytes: Uint8Array, start: number, end: number): Box[] {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const result: Box[] = [];
  let p = start;
  while (p < end) {
    if (end - p < 8) throw new InvalidAudio("truncated_box_header");
    const size32 = u32(view, p);
    const type = ascii(bytes, p + 4, 4);
    let header = 8;
    let size = size32;
    if (size32 === 1) {
      if (end - p < 16) throw new InvalidAudio("truncated_large_box");
      const high = u32(view, p + 8), low = u32(view, p + 12);
      if (high !== 0) throw new InvalidAudio("oversized_box");
      size = low; header = 16;
    } else if (size32 === 0) {
      if (type !== "mdat" || p + 8 > end) throw new InvalidAudio("invalid_open_box");
      size = end - p;
    }
    if (size < header || size > end - p) throw new InvalidAudio("invalid_box_size");
    result.push({ type, start: p, data: p + header, end: p + size });
    p += size;
  }
  if (p !== end) throw new InvalidAudio("box_boundary_mismatch");
  return result;
}

function one(items: Box[], type: string): Box {
  const found = items.filter((b) => b.type === type);
  if (found.length !== 1) throw new InvalidAudio(`invalid_${type}`);
  return found[0];
}

function fullBoxVersion(bytes: Uint8Array, box: Box): number {
  if (box.end - box.data < 4) throw new InvalidAudio("truncated_full_box");
  return bytes[box.data];
}

function mediaDuration(bytes: Uint8Array, box: Box): { timescale: number; duration: number } {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const version = fullBoxVersion(bytes, box);
  const offset = version === 0 ? 12 : version === 1 ? 20 : -1;
  const width = version === 0 ? 4 : 8;
  if (offset < 0 || box.end - box.data < offset + 4 + width) throw new InvalidAudio("invalid_media_header");
  const timescale = u32(view, box.data + offset);
  let duration: number;
  if (width === 4) duration = u32(view, box.data + offset + 4);
  else {
    const high = u32(view, box.data + offset + 4), low = u32(view, box.data + offset + 8);
    if (high > 0x1fffff) throw new InvalidAudio("duration_overflow");
    duration = high * 0x100000000 + low;
  }
  if (!timescale || !duration || !Number.isSafeInteger(duration)) throw new InvalidAudio("invalid_media_duration");
  return { timescale, duration };
}

function descriptorLength(bytes: Uint8Array, start: number, end: number): { length: number; next: number } {
  let value = 0;
  for (let i = 0; i < 4; i++) {
    if (start + i >= end) throw new InvalidAudio("truncated_descriptor");
    const byte = bytes[start + i];
    value = value * 128 + (byte & 0x7f);
    if (!(byte & 0x80)) return { length: value, next: start + i + 1 };
  }
  throw new InvalidAudio("invalid_descriptor_length");
}

function parseDescriptors(bytes: Uint8Array, start: number, end: number, depth = 0): { objectType: number | null; aot: number | null } {
  if (depth > 4) throw new InvalidAudio("descriptor_depth");
  let p = start;
  let objectType: number | null = null;
  let aot: number | null = null;
  while (p < end) {
    if (end - p < 2) throw new InvalidAudio("truncated_descriptor_tag");
    const tag = bytes[p++];
    const parsed = descriptorLength(bytes, p, end);
    p = parsed.next;
    const stop = p + parsed.length;
    if (stop > end) throw new InvalidAudio("descriptor_out_of_bounds");
    if (tag === 3) {
      if (stop - p < 3) throw new InvalidAudio("truncated_es_descriptor");
      const flags = bytes[p + 2];
      let child = p + 3;
      if (flags & 0x80) child += 2;
      if (flags & 0x40) {
        if (child >= stop) throw new InvalidAudio("truncated_url_descriptor");
        child += 1 + bytes[child];
      }
      if (flags & 0x20) child += 2;
      const nested = parseDescriptors(bytes, child, stop, depth + 1);
      objectType ??= nested.objectType;
      aot ??= nested.aot;
    } else if (tag === 4) {
      if (stop - p < 13) throw new InvalidAudio("truncated_decoder_config");
      objectType = bytes[p];
      const nested = parseDescriptors(bytes, p + 13, stop, depth + 1);
      aot ??= nested.aot;
    } else if (tag === 5) {
      if (stop <= p) throw new InvalidAudio("empty_decoder_specific_info");
      const objectAudioType = bytes[p] >> 3;
      if (objectAudioType === 2) aot = objectAudioType;
    }
    p = stop;
  }
  if (p !== end) throw new InvalidAudio("descriptor_boundary_mismatch");
  return { objectType, aot };
}

function verifySampleTables(bytes: Uint8Array, stbl: Box, mdat: Box): { samples: number; durationTicks: number } {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const stts = one(boxes(bytes, stbl.data, stbl.end), "stts");
  if (fullBoxVersion(bytes, stts) !== 0 || stts.end - stts.data < 8) throw new InvalidAudio("invalid_stts");
  const entries = u32(view, stts.data + 4);
  if (!entries || entries > 1000 || stts.data + 8 + entries * 8 !== stts.end) throw new InvalidAudio("invalid_stts_entries");
  let samples = 0, durationTicks = 0;
  for (let i = 0; i < entries; i++) {
    const count = u32(view, stts.data + 8 + i * 8), delta = u32(view, stts.data + 12 + i * 8);
    if (!count || !delta || samples + count > 10_000) throw new InvalidAudio("invalid_sample_timing");
    samples += count; durationTicks += count * delta;
    if (!Number.isSafeInteger(durationTicks)) throw new InvalidAudio("sample_timing_overflow");
  }
  const stsz = one(boxes(bytes, stbl.data, stbl.end), "stsz");
  if (fullBoxVersion(bytes, stsz) !== 0 || stsz.end - stsz.data < 12) throw new InvalidAudio("invalid_stsz");
  const commonSize = u32(view, stsz.data + 4), sampleCount = u32(view, stsz.data + 8);
  if (!sampleCount || sampleCount !== samples || sampleCount > 10_000) throw new InvalidAudio("sample_count_mismatch");
  let totalBytes = 0;
  if (commonSize) totalBytes = commonSize * sampleCount;
  else {
    if (stsz.data + 12 + sampleCount * 4 !== stsz.end) throw new InvalidAudio("invalid_stsz_entries");
    for (let i = 0; i < sampleCount; i++) {
      const size = u32(view, stsz.data + 12 + i * 4);
      if (!size) throw new InvalidAudio("empty_audio_sample");
      totalBytes += size;
    }
  }
  if (totalBytes <= 0 || totalBytes > mdat.end - mdat.data) throw new InvalidAudio("sample_data_outside_mdat");

  const stsc = one(boxes(bytes, stbl.data, stbl.end), "stsc");
  if (fullBoxVersion(bytes, stsc) !== 0 || stsc.end - stsc.data < 8) throw new InvalidAudio("invalid_stsc");
  const mapCount = u32(view, stsc.data + 4);
  if (!mapCount || mapCount > 1000 || stsc.data + 8 + mapCount * 12 !== stsc.end
      || u32(view, stsc.data + 8) !== 1 || !u32(view, stsc.data + 12)) throw new InvalidAudio("invalid_stsc_entries");
  const chunkBox = one(boxes(bytes, stbl.data, stbl.end), "stco");
  if (fullBoxVersion(bytes, chunkBox) !== 0 || chunkBox.end - chunkBox.data < 8) throw new InvalidAudio("invalid_stco");
  const chunkCount = u32(view, chunkBox.data + 4);
  if (!chunkCount || chunkCount > 10_000 || chunkBox.data + 8 + chunkCount * 4 !== chunkBox.end)
    throw new InvalidAudio("invalid_chunk_offsets");
  for (let i = 0; i < chunkCount; i++) {
    const offset = u32(view, chunkBox.data + 8 + i * 4);
    if (offset < mdat.data || offset >= mdat.end) throw new InvalidAudio("chunk_outside_mdat");
  }
  return { samples, durationTicks };
}

export function verifyAacLcM4a(bytes: Uint8Array): { durationMs: number } {
  if (bytes.length < 32 || bytes.length > MAX_BYTES) throw new InvalidAudio("audio_size_invalid");
  const top = boxes(bytes, 0, bytes.length);
  const ftyp = one(top, "ftyp"), moov = one(top, "moov"), mdat = one(top, "mdat");
  if (ftyp.end - ftyp.data < 8) throw new InvalidAudio("invalid_ftyp");
  const majorBrand = ascii(bytes, ftyp.data, 4);
  if (!["M4A ", "isom", "iso2", "mp41", "mp42"].includes(majorBrand)) throw new InvalidAudio("unsupported_container");
  const movieBoxes = boxes(bytes, moov.data, moov.end);
  const movieHeader = one(movieBoxes, "mvhd");
  const movieDuration = mediaDuration(bytes, movieHeader);
  const tracks = movieBoxes.filter((box) => box.type === "trak");
  if (tracks.length !== 1) throw new InvalidAudio("audio_only_track_required");
  const trak = boxes(bytes, tracks[0].data, tracks[0].end);
  const mdia = boxes(bytes, one(trak, "mdia").data, one(trak, "mdia").end);
  const handler = one(mdia, "hdlr");
  if (handler.end - handler.data < 12 || ascii(bytes, handler.data + 8, 4) !== "soun")
    throw new InvalidAudio("audio_track_required");
  const mediaHeader = mediaDuration(bytes, one(mdia, "mdhd"));
  const minf = boxes(bytes, one(mdia, "minf").data, one(mdia, "minf").end);
  const stbl = one(minf, "stbl");
  const tableBoxes = boxes(bytes, stbl.data, stbl.end);
  const stsd = one(tableBoxes, "stsd");
  if (fullBoxVersion(bytes, stsd) !== 0 || stsd.end - stsd.data < 8 || u32(new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength), stsd.data + 4) !== 1)
    throw new InvalidAudio("invalid_sample_description");
  const entry = one(boxes(bytes, stsd.data + 8, stsd.end), "mp4a");
  if (entry.end - entry.data < 28) throw new InvalidAudio("truncated_mp4a");
  const entryView = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const channels = entryView.getUint16(entry.data + 16, false);
  const sampleRate = entryView.getUint32(entry.data + 24, false) >>> 16;
  if (channels < 1 || channels > 2 || sampleRate < 8000 || sampleRate > 48000)
    throw new InvalidAudio("audio_format_out_of_range");
  const children = boxes(bytes, entry.data + 28, entry.end);
  const esds = one(children, "esds");
  if (fullBoxVersion(bytes, esds) !== 0) throw new InvalidAudio("unsupported_esds_version");
  const descriptor = parseDescriptors(bytes, esds.data + 4, esds.end);
  if (descriptor.objectType !== 0x40 || descriptor.aot !== 2) throw new InvalidAudio("aac_lc_required");

  const sampleTiming = verifySampleTables(bytes, stbl, mdat);
  const durationMs = Math.round(sampleTiming.durationTicks * 1000 / mediaHeader.timescale);
  const mdhdMs = Math.round(mediaHeader.duration * 1000 / mediaHeader.timescale);
  const mvhdMs = Math.round(movieDuration.duration * 1000 / movieDuration.timescale);
  if (durationMs < 1 || durationMs > MAX_DURATION_MS || Math.abs(durationMs - mdhdMs) > 1000
      || Math.abs(durationMs - mvhdMs) > 1000) throw new InvalidAudio("audio_duration_invalid");
  return { durationMs };
}
