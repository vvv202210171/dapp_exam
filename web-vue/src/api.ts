// 后端错误统一是 { error: { code, message, hint, retryable } }。页面用 hint 告诉用户下一步。
import type { ApiErrorBody } from "./types";

export class ApiError extends Error {
  code: string;
  hint?: string;
  retryable: boolean;

  // 用后端的 code、message、hint 构造一个前端错误。
  constructor(error: ApiErrorBody["error"]) {
    super(error.message);
    this.code = error.code;
    this.hint = error.hint;
    this.retryable = Boolean(error.retryable);
  }
}

// 请求 /api，失败时抛出带提示的 ApiError。
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...(init?.headers ?? {}),
      },
    });
  } catch {
    throw new ApiError({
      code: "NETWORK",
      message: "连不上后端",
      hint: "先在 server 目录执行 go run ./cmd/api，再刷新这个页面。",
      retryable: true,
    });
  }
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    if (data?.error?.message) throw new ApiError(data.error);
    throw new ApiError({ code: "HTTP", message: `请求失败（${response.status}）` });
  }
  return data as T;
}
