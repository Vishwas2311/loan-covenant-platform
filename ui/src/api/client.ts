import { authHeaders } from '../auth/identity';

/** Error envelope shared by Core and DPS: { code, message, details? }. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function toError(response: Response): Promise<ApiError> {
  let body: { code?: string; message?: string; error?: string; details?: unknown } | undefined;
  try {
    body = await response.json();
  } catch {
    body = undefined;
  }
  const message = body?.message ?? body?.error ?? (response.statusText || `HTTP ${response.status}`);
  return new ApiError(message, response.status, body?.code, body?.details);
}

export interface RequestOptions extends Omit<RequestInit, 'body'> {
  json?: unknown;
  body?: BodyInit;
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { json, headers, body, ...rest } = options;
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData;
  const response = await fetch(path, {
    ...rest,
    headers: {
      Accept: 'application/json',
      ...(json !== undefined && !isForm ? { 'Content-Type': 'application/json' } : {}),
      ...authHeaders(),
      ...(headers as Record<string, string> | undefined),
    },
    body: json !== undefined ? JSON.stringify(json) : body,
  });
  if (!response.ok) {
    throw await toError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const type = response.headers.get('content-type') ?? '';
  return (type.includes('application/json') ? await response.json() : await response.text()) as T;
}

/** Downloads a file through fetch (so the identity headers go with it) and saves it in the browser. */
export async function download(path: string, fallbackName: string): Promise<void> {
  const response = await fetch(path, { headers: authHeaders() });
  if (!response.ok) {
    throw await toError(response);
  }
  const blob = await response.blob();
  const header = response.headers.get('content-disposition') ?? '';
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  const name = match ? decodeURIComponent(match[1]) : fallbackName;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.status === 404 ? 'Not found, or you do not have access.' : error.message;
  }
  return error instanceof Error ? error.message : String(error);
}
