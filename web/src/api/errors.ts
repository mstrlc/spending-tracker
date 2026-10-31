import type { ApiErrorBody } from "./types";

/** A non-2xx API response, carrying the server's error code and the offending field, if any. */
export class ApiFailure extends Error {
  readonly status: number;
  readonly code: string;
  readonly field?: string;

  constructor(status: number, body: Partial<ApiErrorBody> | undefined) {
    super(body?.message ?? `Request failed (${status})`);
    this.status = status;
    this.code = body?.code ?? "unknown";
    this.field = body?.field ?? undefined;
  }
}

/** Unwraps an openapi-fetch result: returns data, or throws ApiFailure. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined || !result.response.ok) {
    throw new ApiFailure(result.response.status, result.error as Partial<ApiErrorBody> | undefined);
  }
  return result.data as T;
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Something went wrong";
}
