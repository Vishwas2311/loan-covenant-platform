import { setAccessToken, setIdentity } from '../auth/identity';
import { ApiError, api, errorMessage } from './client';
import { mockFetch } from '../test-utils';

afterEach(() => {
  vi.unstubAllGlobals();
  setAccessToken(null);
});

describe('api client', () => {
  it('sends dev identity headers and JSON bodies', async () => {
    setIdentity({ user: 'approver-1', roles: ['APPROVER'] });
    const calls = mockFetch(() => ({ body: { ok: true } }));
    await expect(api('/api/v1/x', { method: 'POST', json: { a: 1 } })).resolves.toEqual({ ok: true });
    const headers = calls[0].init.headers as Record<string, string>;
    expect(headers['X-User']).toBe('approver-1');
    expect(headers['X-Roles']).toBe('APPROVER');
    expect(headers['Content-Type']).toBe('application/json');
    expect(calls[0].init.body).toBe('{"a":1}');
  });

  it('prefers a bearer token when signed in', async () => {
    setAccessToken('tok');
    const calls = mockFetch(() => ({ status: 204 }));
    await expect(api('/x')).resolves.toBeUndefined();
    const headers = calls[0].init.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer tok');
    expect(headers['X-User']).toBeUndefined();
  });

  it('maps the error envelope', async () => {
    mockFetch(() => ({ status: 409, body: { code: 'JOB_NOT_WAITING', message: 'Job is DONE' } }));
    const err = (await api("/x").catch((e: unknown) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('JOB_NOT_WAITING');
    expect(errorMessage(err)).toBe('Job is DONE');
    expect(errorMessage(new ApiError('x', 404))).toMatch(/no access|do not have access/);
    expect(errorMessage(new Error('boom'))).toBe('boom');
    expect(errorMessage('plain')).toBe('plain');
  });

  it('does not set a JSON content type for form uploads', async () => {
    const calls = mockFetch(() => ({ body: {} }));
    const form = new FormData();
    await api('/dps/v1/documents', { method: 'POST', body: form });
    expect((calls[0].init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
  });

  it('reads text responses', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('hello', { headers: { 'content-type': 'text/plain' } })));
    await expect(api('/x')).resolves.toBe('hello');
  });

  it('falls back to the status text when the body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('oops', { status: 500, statusText: 'Server Error' })));
    await expect(api('/x')).rejects.toMatchObject({ status: 500, message: 'Server Error' });
  });
});
