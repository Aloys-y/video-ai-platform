import { createHash } from 'node:crypto';
import { mkdir, open } from 'node:fs/promises';
import path from 'node:path';
import { performance } from 'node:perf_hooks';

const BASE = process.env.BENCH_BASE ?? 'http://localhost:8083/api';
const EMAIL = process.env.BENCH_EMAIL ?? 'test@videoai.com';
const PASSWORD = process.env.BENCH_PASSWORD ?? 'test123';
const SAMPLE_SIZE = 2 * 1024 * 1024;
const FILE_SIZE_MB = Number(process.env.BENCH_FILE_SIZE_MB ?? 500);
const BASELINE_CHUNK_SIZE_MB = Number(process.env.BENCH_BASELINE_CHUNK_MB ?? 500);
const BASELINE_CONCURRENCY = Number(process.env.BENCH_BASELINE_CONCURRENCY ?? 1);
const COMPARE_CHUNK_SIZE_MB = Number(process.env.BENCH_COMPARE_CHUNK_MB ?? 5);
const COMPARE_CONCURRENCY = Number(process.env.BENCH_COMPARE_CONCURRENCY ?? 3);

function toBytes(megabytes) {
  return megabytes * 1024 * 1024;
}

async function api(pathname, options = {}) {
  const response = await fetch(`${BASE}${pathname}`, options);
  const text = await response.text();
  let json;

  try {
    json = JSON.parse(text);
  } catch (error) {
    throw new Error(`Non-JSON response from ${pathname}: ${text.slice(0, 300)}`);
  }

  if (!response.ok || !json.success) {
    throw new Error(`${pathname} failed: ${json.message ?? response.statusText}`);
  }

  return json.data;
}

async function login() {
  const auth = await api('/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      email: EMAIL,
      password: PASSWORD,
    }),
  });

  if (!auth?.token) {
    throw new Error('Login succeeded but token is missing');
  }

  return auth.token;
}

async function createBenchmarkFile(filePath, sizeBytes, seed) {
  await mkdir(path.dirname(filePath), { recursive: true });

  const handle = await open(filePath, 'w');
  try {
    await handle.truncate(sizeBytes);

    const headMarker = Buffer.from(`HEAD-${seed}`.padEnd(64, '#'));
    const middleMarker = Buffer.from(`MID-${seed}`.padEnd(64, '#'));
    const tailMarker = Buffer.from(`TAIL-${seed}`.padEnd(64, '#'));

    await handle.write(headMarker, 0, headMarker.length, 0);
    await handle.write(
      middleMarker,
      0,
      middleMarker.length,
      Math.max(Math.floor(sizeBytes / 2) - Math.floor(middleMarker.length / 2), 0),
    );
    await handle.write(
      tailMarker,
      0,
      tailMarker.length,
      Math.max(sizeBytes - tailMarker.length, 0),
    );
  } finally {
    await handle.close();
  }
}

async function readRange(handle, start, length) {
  const buffer = Buffer.alloc(length);
  const { bytesRead } = await handle.read(buffer, 0, length, start);
  return bytesRead === length ? buffer : buffer.subarray(0, bytesRead);
}

async function computeSampleHash(filePath, fileSize) {
  const handle = await open(filePath, 'r');
  try {
    const parts = [];
    const firstLength = Math.min(SAMPLE_SIZE, fileSize);
    parts.push(await readRange(handle, 0, firstLength));

    if (fileSize > SAMPLE_SIZE * 2) {
      const middleStart = Math.floor((fileSize - SAMPLE_SIZE) / 2);
      parts.push(await readRange(handle, middleStart, SAMPLE_SIZE));
    }

    if (fileSize > SAMPLE_SIZE) {
      const tailStart = fileSize - SAMPLE_SIZE;
      parts.push(await readRange(handle, tailStart, SAMPLE_SIZE));
    }

    const sizeBuffer = Buffer.alloc(8);
    sizeBuffer.writeDoubleLE(fileSize, 0);
    parts.push(sizeBuffer);

    const hash = createHash('sha256');
    for (const part of parts) {
      hash.update(part);
    }
    return hash.digest('hex');
  } finally {
    await handle.close();
  }
}

async function initUpload(token, scenario) {
  const fileHash = await computeSampleHash(scenario.filePath, scenario.fileSize);

  return api('/upload/init', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      fileName: path.basename(scenario.filePath),
      fileSize: scenario.fileSize,
      chunkSize: scenario.chunkSize,
      contentType: 'video/mp4',
      fileHash,
    }),
  });
}

async function uploadChunk(token, uploadId, chunkIndex, buffer) {
  const formData = new FormData();
  formData.append('file', new Blob([buffer]), `chunk-${chunkIndex}.bin`);

  return api('/upload/chunk', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'X-Upload-Id': uploadId,
      'X-Chunk-Index': String(chunkIndex),
    },
    body: formData,
  });
}

async function uploadScenario(token, scenario) {
  const initStart = performance.now();
  const initResult = await initUpload(token, scenario);
  const initMs = performance.now() - initStart;

  if (initResult.instantUpload) {
    throw new Error(`${scenario.name} became instant upload, benchmark is invalid`);
  }

  const uploadId = initResult.uploadId;
  const chunkSize = initResult.chunkSize ?? scenario.chunkSize;
  const totalChunks = initResult.totalChunks ?? Math.ceil(scenario.fileSize / chunkSize);
  const uploadedChunks = new Set(initResult.uploadedChunks ?? []);
  const pending = [];

  for (let index = 0; index < totalChunks; index += 1) {
    if (!uploadedChunks.has(index)) {
      pending.push(index);
    }
  }

  const handle = await open(scenario.filePath, 'r');
  try {
    const uploadStart = performance.now();
    let cursor = 0;
    const workerCount = Math.min(scenario.concurrency, pending.length || 1);

    const workers = Array.from({ length: workerCount }, async () => {
      while (cursor < pending.length) {
        const current = cursor;
        cursor += 1;
        const chunkIndex = pending[current];
        const start = chunkIndex * chunkSize;
        const length = Math.min(chunkSize, scenario.fileSize - start);
        const buffer = await readRange(handle, start, length);
        await uploadChunk(token, uploadId, chunkIndex, buffer);
      }
    });

    await Promise.all(workers);
    const uploadMs = performance.now() - uploadStart;

    const completeStart = performance.now();
    await api('/upload/complete', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'X-Upload-Id': uploadId,
      },
    });
    const completeMs = performance.now() - completeStart;

    return {
      name: scenario.name,
      uploadId,
      fileSize: scenario.fileSize,
      chunkSize,
      totalChunks,
      concurrency: scenario.concurrency,
      initMs: Number(initMs.toFixed(1)),
      uploadMs: Number(uploadMs.toFixed(1)),
      completeMs: Number(completeMs.toFixed(1)),
      totalMs: Number((initMs + uploadMs + completeMs).toFixed(1)),
    };
  } finally {
    await handle.close();
  }
}

async function main() {
  const now = Date.now();
  const sizeBytes = toBytes(FILE_SIZE_MB);
  const tempDir = path.resolve('tmp-bench');
  const singleFile = path.join(tempDir, `single-${now}.mp4`);
  const concurrentFile = path.join(tempDir, `concurrent-${now}.mp4`);

  await createBenchmarkFile(singleFile, sizeBytes, `single-${now}`);
  await createBenchmarkFile(concurrentFile, sizeBytes, `concurrent-${now}`);

  const token = await login();
  const scenarios = [
    {
      name: `e2e-${BASELINE_CHUNK_SIZE_MB}mb-c${BASELINE_CONCURRENCY}-${FILE_SIZE_MB}mb`,
      filePath: singleFile,
      fileSize: sizeBytes,
      chunkSize: toBytes(BASELINE_CHUNK_SIZE_MB),
      concurrency: BASELINE_CONCURRENCY,
    },
    {
      name: `e2e-${COMPARE_CHUNK_SIZE_MB}mb-c${COMPARE_CONCURRENCY}-${FILE_SIZE_MB}mb`,
      filePath: concurrentFile,
      fileSize: sizeBytes,
      chunkSize: toBytes(COMPARE_CHUNK_SIZE_MB),
      concurrency: COMPARE_CONCURRENCY,
    },
  ];

  const results = [];
  for (const scenario of scenarios) {
    console.log(`Running ${scenario.name} ...`);
    const result = await uploadScenario(token, scenario);
    results.push(result);
    console.log(JSON.stringify(result, null, 2));
  }

  if (results.length === 2) {
    const [single, concurrent] = results;
    const gain = ((single.totalMs - concurrent.totalMs) / single.totalMs) * 100;
    console.log(
      JSON.stringify(
        {
          baseline: single.name,
          compare: concurrent.name,
          totalGainPercent: Number(gain.toFixed(2)),
        },
        null,
        2,
      ),
    );
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
