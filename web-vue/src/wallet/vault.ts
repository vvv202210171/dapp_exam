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
  // 16 字节随机盐。同样的密码配上不同的盐，会算出不同的密钥。
  const salt = crypto.getRandomValues(new Uint8Array(16));
  // 12 字节随机 IV，这是 AES-GCM 推荐的 nonce 长度。同一把密钥不能重复使用同一个 IV。
  const iv = crypto.getRandomValues(new Uint8Array(12));
  // 密码加盐，PBKDF2-SHA256 迭代 21 万次，得到 256 位 AES-GCM 密钥。
  const key = await deriveKey(password, salt, ITERATIONS);
  // 把助记词和账户名收成 JSON，再编成 UTF-8 字节。这就是要加密的明文。
  const plain = new TextEncoder().encode(JSON.stringify({ mnemonic, label } satisfies Secret));
  // AES-GCM 加密。返回的字节是密文，末尾还带 16 字节认证标签。
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plain));
  // 信封：版本、算法名、迭代次数，以及盐、IV、密文。后三样都是二进制，先转成 Base64。
  const envelope: Envelope = {
    v: 1,                              // 信封版本，以后换算法时用来识别旧数据。
    kdf: "PBKDF2-SHA256",              // 记下密钥是怎么推导的。
    iterations: ITERATIONS,            // 记下迭代次数，解密时必须用同一个数字。
    salt: toB64(salt),                 // 盐。没有它就无法从密码还原密钥。
    iv: toB64(iv),                     // IV。没有它就无法把密文解回明文。
    ct: toB64(ct),                     // 密文加认证标签。
  };
  // 整份信封序列化成 JSON，放进 localStorage。明文助记词不落盘。
  localStorage.setItem(STORAGE_KEY, JSON.stringify(envelope));
}

// 用密码解开保险库。密码错或数据坏都会失败。
export async function openVault(password: string): Promise<Secret> {
  // 取出上次 sealVault 写下的信封。
  const raw = localStorage.getItem(STORAGE_KEY);
  // 没有信封，说明这台浏览器还没创建过钱包。
  if (!raw) throw new Error("这台浏览器里还没有钱包");
  // 把 JSON 还原成盐、IV、密文和迭代次数。
  const envelope = JSON.parse(raw) as Envelope;
  // 用用户这次输入的密码，配上信封里保存的盐和迭代次数，再推导一次密钥。
  const key = await deriveKey(password, fromB64(envelope.salt), envelope.iterations);
  try {
    // AES-GCM 解密。密钥、IV 或密文任一不对，认证标签就过不了，这里会抛错。
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: toArrayBuffer(fromB64(envelope.iv)) }, // 用回加密时的那个 IV。
      key,                                                           // 刚由密码重新算出的密钥。
      toArrayBuffer(fromB64(envelope.ct)),                           // Base64 解回的密文和认证标签。
    );
    // 明文是 UTF-8 JSON，解出来就是助记词和账户名。
    return JSON.parse(new TextDecoder().decode(plain)) as Secret;
  } catch {
    // GCM 不说明失败原因。密码错误和保险库被改过，表现一样。
    throw new Error("密码不对，或者本机保险库已经损坏");
  }
}

// 用密码和盐做 PBKDF2，得到 AES-GCM 密钥。
async function deriveKey(password: string, salt: Uint8Array, iterations: number) {
  // 复制到一块新的 ArrayBuffer。WebCrypto 要的是这种缓冲区，不能直接拿 Uint8Array 的视图。
  const saltBuffer = new ArrayBuffer(salt.byteLength);
  // 把盐的每个字节抄进这块新缓冲区。
  new Uint8Array(saltBuffer).set(salt);
  // 把密码的 UTF-8 字节导入成 PBKDF2 的原料密钥。false 表示这把原料密钥不能再导出。
  const base = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveKey"]);
  // HMAC-SHA256 重复 iterations 次，拉伸出 256 位 AES-GCM 密钥。只能加密和解密，不能导出。
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: saltBuffer, iterations, hash: "SHA-256" }, // 盐、次数、哈希算法必须和加密时一致。
    base,                                                               // 上一步导入的密码字节。
    { name: "AES-GCM", length: 256 },                                   // 要的是 256 位 AES-GCM 密钥。
    false,                                                              // 派生结果也不允许导出，只留在 WebCrypto 内部。
    ["encrypt", "decrypt"],                                             // 这把密钥的用途：加密和解密。
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
