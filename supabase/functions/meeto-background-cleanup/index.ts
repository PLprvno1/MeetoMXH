// One-shot Storage cleanup worker. Authorization is a DB-minted, single-use
// nonce; platform secret keys never leave the Edge Function runtime.
import { InvalidAudio, verifyAacLcM4a } from "./audio_verifier.ts";
const encoder = new TextEncoder();
const AUDIO_MAX_BYTES = 2 * 1024 * 1024;
const UUID_PATTERN = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

async function fetchWithTimeout(url: string, init: RequestInit, ms = 15_000): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

function cleanupExtension(mime: unknown): string | null {
  switch (mime) {
    case "image/png": return "png";
    case "image/jpeg": return "jpg";
    case "image/webp": return "webp";
    case "video/mp4": return "mp4";
    case "audio/mp4": return "m4a";
    default: return null;
  }
}

function validCleanupClaim(item: Record<string, unknown>): boolean {
  const eventId = item.event_id, assetId = item.asset_id, ownerId = item.owner_id;
  const bucket = item.bucket_id, purpose = item.purpose, mime = item.mime_type;
  const objectPath = item.object_path, attempt = item.attempt;
  if (typeof eventId !== "string" || !UUID_PATTERN.test(eventId)
      || typeof assetId !== "string" || !UUID_PATTERN.test(assetId)
      || typeof ownerId !== "string" || !UUID_PATTERN.test(ownerId)
      || !Number.isInteger(attempt) || (attempt as number) < 1 || (attempt as number) > 5
      || typeof objectPath !== "string" || objectPath.length > 512) return false;

  const allowed = bucket === "profile-media"
    ? (purpose === "avatar" || purpose === "cover")
      && ["image/png", "image/jpeg", "image/webp"].includes(String(mime))
    : bucket === "social-media"
      ? (purpose === "post" || purpose === "story")
        && ["image/png", "image/jpeg", "image/webp", "video/mp4"].includes(String(mime))
      : bucket === "chat-media"
        ? purpose === "message"
          && ["image/png", "image/jpeg", "image/webp", "video/mp4", "audio/mp4"].includes(String(mime))
        : false;
  const extension = cleanupExtension(mime);
  return allowed && extension !== null
    && objectPath === ownerId + "/" + assetId + "/original." + extension;
}

type StorageObservation = "absent" | "present" | "unknown";

async function observeStorageObject(
  supabaseUrl: string, secretKey: string, bucket: string, objectPath: string,
): Promise<StorageObservation> {
  const encodedPath = objectPath.split("/").map(encodeURIComponent).join("/");
  try {
    const bucketCheck = await fetchWithTimeout(supabaseUrl + "/storage/v1/bucket/" + bucket, {
      method: "GET", headers: { apikey: secretKey, authorization: "Bearer " + secretKey },
    });
    if (!bucketCheck.ok) {
      try { await bucketCheck.body?.cancel(); } catch { /* no response body is needed */ }
      return "unknown";
    }
    try { await bucketCheck.body?.cancel(); } catch { /* no response body is needed */ }
    const objectCheck = await fetchWithTimeout(
      supabaseUrl + "/storage/v1/object/authenticated/" + bucket + "/" + encodedPath,
      { method: "HEAD", headers: { apikey: secretKey, authorization: "Bearer " + secretKey } },
    );
    if (objectCheck.status === 404) return "absent";
    if (objectCheck.ok) return "present";
    return "unknown";
  } catch {
    return "unknown";
  }
}

async function readBounded(response: Response, maxBytes: number): Promise<Uint8Array> {
  const declared = Number(response.headers.get("content-length") ?? "0");
  if (declared > maxBytes) throw new InvalidAudio("audio_size_invalid");
  const reader = response.body?.getReader();
  if (!reader) throw new InvalidAudio("audio_body_missing");
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw new InvalidAudio("audio_size_invalid");
    }
    chunks.push(value);
  }
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.byteLength; }
  return result;
}

async function verifyVoiceAsset(
  item: Record<string, unknown>, supabaseUrl: string, secretKey: string,
): Promise<"verified" | "invalid" | "retry"> {
  const assetId = item.asset_id, ownerId = item.owner_id, bucket = item.bucket_id;
  const objectPath = item.object_path, objectId = item.object_id, attempt = item.attempt;
  const expectedSize = item.expected_size;
  if (typeof assetId !== "string" || typeof ownerId !== "string" || bucket !== "chat-media"
      || typeof objectPath !== "string" || !Number.isInteger(attempt) || typeof objectId !== "string"
      || typeof expectedSize !== "number" || expectedSize < 1 || expectedSize > AUDIO_MAX_BYTES
      || !/^[0-9a-f-]{36}$/i.test(assetId) || !/^[0-9a-f-]{36}$/i.test(ownerId)
      || objectPath.length > 512 || objectPath.split("/").length !== 3
      || objectPath !== `${ownerId}/${assetId}/original.m4a` || item.mime_type !== "audio/mp4") return "invalid";
  const encodedPath = objectPath.split("/").map(encodeURIComponent).join("/");
  let bytes: Uint8Array;
  const controller = new AbortController();
  // Keep the deadline active through body streaming. fetch() alone resolves at
  // response headers, which used to leave a stalled Storage body unbounded.
  const downloadTimer = setTimeout(() => controller.abort(), 25_000);
  try {
    const response = await fetch(`${supabaseUrl}/storage/v1/object/authenticated/${bucket}/${encodedPath}`, {
      method: "GET", headers: { apikey: secretKey, authorization: `Bearer ${secretKey}` },
      signal: controller.signal,
    });
    if (response.status === 404 || response.status === 410) return "invalid";
    if (!response.ok) return "retry";
    if (response.headers.get("content-type")?.split(";")[0].trim().toLowerCase() !== "audio/mp4") return "invalid";
    bytes = await readBounded(response, AUDIO_MAX_BYTES);
  } catch (error) {
    return error instanceof InvalidAudio ? "invalid" : "retry";
  } finally {
    clearTimeout(downloadTimer);
  }
  if (bytes.byteLength !== expectedSize) return "invalid";
  let durationMs: number;
  try { durationMs = verifyAacLcM4a(bytes).durationMs; }
  catch (error) { return error instanceof InvalidAudio ? "invalid" : "retry"; }
  try {
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    const sha256 = [...digest].map((byte) => byte.toString(16).padStart(2, "0")).join("");
    const finish = await fetchWithTimeout(`${supabaseUrl}/rest/v1/rpc/meeto_worker_finish_voice_verification`, {
      method: "POST", headers: { "content-type": "application/json", apikey: secretKey,
        authorization: `Bearer ${secretKey}` },
      body: JSON.stringify({ p_asset_id: assetId, p_attempt: attempt, p_outcome: "verified",
        p_object_id: objectId, p_bytes: bytes.byteLength, p_sha256: sha256, p_duration_ms: durationMs }),
    });
    if (!finish.ok || (await finish.json()) !== true) return "retry";
    return "verified";
  } catch { return "retry"; }
}

Deno.serve(async (request: Request): Promise<Response> => {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const declaredLength = Number(request.headers.get("content-length") ?? "0");
  if (declaredLength > 1024) return json({ error: "request_too_large" }, 413);

  let body: { token?: unknown };
  try {
    const reader = request.body?.getReader();
    if (!reader) return json({ error: "invalid_request" }, 400);
    const chunks: Uint8Array[] = [];
    let total = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > 1024) {
        await reader.cancel();
        return json({ error: "request_too_large" }, 413);
      }
      chunks.push(value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    body = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return json({ error: "invalid_request" }, 400);
  }
  if (typeof body.token !== "string" || !/^[0-9a-f]{64}$/.test(body.token)) {
    return json({ error: "unauthorized" }, 401);
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const secretMapRaw = Deno.env.get("SUPABASE_SECRET_KEYS");
  if (!supabaseUrl || !secretMapRaw) return json({ error: "worker_unavailable" }, 503);
  let secretKey: string;
  try {
    const secretMap = JSON.parse(secretMapRaw) as Record<string, unknown>;
    if (typeof secretMap.default !== "string" || secretMap.default.length < 20) throw new Error();
    secretKey = secretMap.default;
  } catch {
    return json({ error: "worker_unavailable" }, 503);
  }

  const rpc = async (name: string, args: Record<string, unknown>): Promise<Response> =>
    await fetchWithTimeout(`${supabaseUrl}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        apikey: secretKey,
        authorization: `Bearer ${secretKey}`,
      },
      body: JSON.stringify(args),
    });

  let runId: string | null = null;
  try {
    const consume = await rpc("meeto_worker_consume_token", { p_token: body.token });
    if (!consume.ok) return json({ error: "unauthorized" }, 401);
    const consumed = await consume.json();
    runId = typeof consumed === "string" ? consumed : null;
    if (!runId) return json({ error: "unauthorized" }, 401);

    const audioClaimResponse = await rpc("meeto_worker_claim_voice_verification", { p_batch: 1 });
    if (!audioClaimResponse.ok) throw new Error("audio_claim_failed");
    const audioClaims = await audioClaimResponse.json();
    if (!Array.isArray(audioClaims) || audioClaims.length > 1) throw new Error("invalid_audio_claim");
    const audioSummary = { claimed: audioClaims.length, verified: 0, rejected: 0, retried: 0 };
    for (const claimedAudio of audioClaims) {
      const outcome = await verifyVoiceAsset(claimedAudio as Record<string, unknown>, supabaseUrl, secretKey);
      if (outcome === "verified") { audioSummary.verified++; continue; }
      try {
        const finish = await rpc("meeto_worker_finish_voice_verification", {
          p_asset_id: claimedAudio.asset_id, p_attempt: claimedAudio.attempt,
          p_outcome: outcome === "invalid" ? "invalid" : "retry",
          p_object_id: null, p_bytes: null, p_sha256: null, p_duration_ms: null,
        });
        if (!finish.ok || (await finish.json()) !== true) throw new Error("audio_ack_failed");
        if (outcome === "invalid") audioSummary.rejected++;
        else audioSummary.retried++;
      } catch { audioSummary.retried++; }
    }

    const claimedResponse = await rpc("meeto_worker_claim_cleanup", { p_batch: 5 });
    if (!claimedResponse.ok) throw new Error("claim_failed");
    const claimed = await claimedResponse.json();
    if (!Array.isArray(claimed) || claimed.length > 5) throw new Error("invalid_claim_result");

    let deleted = 0;
    let failed = 0;
    let absentConfirmed = 0;
    let present = 0;
    let unknown = 0;
    for (const item of claimed) {
      if (!item || typeof item !== "object") {
        failed++;
        continue;
      }
      const cleanupItem = item as Record<string, unknown>;
      const eventId = cleanupItem.event_id;
      const attempt = cleanupItem.attempt;
      const bucket = cleanupItem.bucket_id;
      const objectPath = cleanupItem.object_path;
      if (typeof eventId !== "string" || !UUID_PATTERN.test(eventId)
          || !Number.isInteger(attempt) || (attempt as number) < 1 || (attempt as number) > 5) {
        failed++;
        continue;
      }

      const contractValid = validCleanupClaim(cleanupItem);
      let deleteStatus: number | null = null;
      let storageOutcome: StorageObservation = "unknown";
      let errorCode = contractValid ? "storage_unknown" : "invalid_cleanup_contract";
      if (contractValid) {
        try {
          const response = await fetchWithTimeout(`${supabaseUrl}/storage/v1/object/${String(bucket)}`, {
            method: "DELETE",
            headers: {
              "content-type": "application/json",
              apikey: secretKey,
              authorization: `Bearer ${secretKey}`,
            },
            body: JSON.stringify({ prefixes: [objectPath] }),
          });
          deleteStatus = response.status;
          // We only need the status. Do not leave an unread Storage response body open.
          try { await response.body?.cancel(); } catch { /* response metadata was already captured */ }
          if (!response.ok) {
            errorCode = response.status === 401 || response.status === 403 ? "storage_denied" : "storage_error";
          }
          // A 2xx response alone does not prove the exact object is absent.
          storageOutcome = await observeStorageObject(supabaseUrl, secretKey, String(bucket), objectPath as string);
        } catch {
          // DELETE may have reached Storage before timing out; probe the exact object.
          storageOutcome = await observeStorageObject(supabaseUrl, secretKey, String(bucket), objectPath as string);
          errorCode = "storage_unavailable";
        }
      }

      if (storageOutcome === "absent") { absentConfirmed++; errorCode = "storage_error"; }
      else if (storageOutcome === "present") { present++; errorCode = "storage_present"; }
      else { unknown++; if (contractValid) errorCode = "storage_unknown"; }

      try {
        const observation = await rpc("meeto_worker_record_cleanup_observation", {
          p_event_id: eventId,
          p_attempt: attempt,
          p_outcome: storageOutcome,
          p_delete_http_status: deleteStatus,
        });
        const observationRecorded = observation.ok && (await observation.json()) === true;
        const ack = await rpc("meeto_worker_finish_cleanup", {
          p_event_id: eventId,
          p_attempt: attempt,
          p_success: observationRecorded && storageOutcome === "absent",
          p_error_code: observationRecorded && storageOutcome === "absent" ? null : errorCode,
        });
        if (!ack.ok || (await ack.json()) !== true) {
          failed++;
          continue;
        }
        if (observationRecorded && storageOutcome === "absent") deleted++;
        else failed++;
      } catch {
        // The persisted observation and attempt fence support later review.
        failed++;
      }
    }

    const finished = await rpc("meeto_worker_finish_run", {
      p_run_id: runId,
      p_summary: { audio: audioSummary, claimed: claimed.length, deleted, failed,
        storage: { absent_confirmed: absentConfirmed, present, unknown } },
    });
    if (!finished.ok || (await finished.json()) !== true) return json({ error: "worker_ack_failed" }, 503);
    return json({ audio: audioSummary, claimed: claimed.length, deleted, failed });
  } catch {
    if (runId) {
      try {
        await rpc("meeto_worker_finish_run", {
          p_run_id: runId,
          p_summary: { failed: true },
        });
      } catch {
        // Expiring invocation/cleanup leases provide recovery.
      }
    }
    return json({ error: "worker_failed" }, 503);
  }
});
