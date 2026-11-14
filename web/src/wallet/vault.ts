// 助记词保险库。只加密后放进 localStorage，明文助记词不落盘。
const STORAGE_KEY = "aegis.vault.v1";
// WebCrypto 没有 Argon2id。21 万次 PBKDF2 是演示取舍，OWASP 对 SHA-256 的建议是 60 万次。
const ITERATIONS = 210_000;

type Envelope = {
  v: 1;
  kdf: "PBKDF2-SHA256";
  iterations: number;
  salt: string;
  iv: string;
  ct: string;
};

type Secret = { mnemonic: string; label: string };

// 这台浏览器里有没有已保存的保险库。
export function hasVault(): boolean {
  return localStorage.getItem(STORAGE_KEY) !== null;
}

// 删除本机保险库。助记词没有其他副本时会找不回来。
export function clearVault(): void {
  localStorage.removeItem(STORAGE_KEY);
}

// 用密码加密助记词和账户名，写入 localStorage。
export async function sealVault(password: string, mnemonic: string, label: string): Promise<void> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(password, salt, ITERATIONS);
  const plain = new TextEncoder().encode(JSON.stringify({ mnemonic, label } satisfies Secret));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plain));
  const envelope: Envelope = {
    v: 1,
    kdf: "PBKDF2-SHA256",
    iterations: ITERATIONS,
    salt: toB64(salt),
    iv: toB64(iv),
    ct: toB64(ct),
  };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(envelope));
}

// 用密码解开保险库。密码错或数据坏都会失败。
export async function openVault(password: string): Promise<Secret> {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) throw new Error("这台浏览器里还没有钱包");
  const envelope = JSON.parse(raw) as Envelope;
  const key = await deriveKey(password, fromB64(envelope.salt), envelope.iterations);
  try {
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: toArrayBuffer(fromB64(envelope.iv)) },
      key,
      toArrayBuffer(fromB64(envelope.ct)),
    );
    return JSON.parse(new TextDecoder().decode(plain)) as Secret;
  } catch {
    throw new Error("密码不对，或者本机保险库已经损坏");
  }
}

// 用密码和盐做 PBKDF2，得到 AES-GCM 密钥。
async function deriveKey(password: string, salt: Uint8Array, iterations: number) {
  // 复制到新的 ArrayBuffer。TS 5.7 里 Uint8Array<ArrayBufferLike> 不能直接当 BufferSource。
  const saltBuffer = new ArrayBuffer(salt.byteLength);
  new Uint8Array(saltBuffer).set(salt);
  const base = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: saltBuffer, iterations, hash: "SHA-256" },
    base,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

// 把字节编成 base64。
function toB64(bytes: Uint8Array): string {
  let text = "";
  for (const byte of bytes) text += String.fromCharCode(byte);
  return btoa(text);
}

// 把 base64 解回字节。
function fromB64(value: string): Uint8Array {
  const text = atob(value);
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i);
  return out;
}

// 复制出一块新的 ArrayBuffer，供 WebCrypto 使用。
function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const out = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(out).set(bytes);
  return out;
}
