import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const manager = {
  events: { addUserLoaded: vi.fn(), addUserUnloaded: vi.fn() },
  signinRedirectCallback: vi.fn(),
  getUser: vi.fn(),
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
};
const settingsSeen: Record<string, unknown>[] = [];

vi.mock('oidc-client-ts', () => ({
  // A class, so `new UserManager(...)` works; it hands back the shared fake above.
  UserManager: class {
    constructor(settings: Record<string, unknown>) {
      settingsSeen.push(settings);
      return manager;
    }
  },
  WebStorageStateStore: class {},
  InMemoryWebStorage: class {},
}));

import { getAccessToken, getIdentity, identityFromToken, setAccessToken } from './identity';
import { loadAuthConfig, signedIn, signOut, startAuth } from './oidc';

function jwt(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) => window.btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${b64({ alg: 'RS256' })}.${b64(claims)}.sig`;
}

const CFG = { authority: 'http://localhost:8180/realms/covenant', clientId: 'covenant-ui', scope: 'openid profile' };
const loc = (pathname: string, search = '') =>
  ({ origin: 'http://localhost:8080', pathname, search } as unknown as Location);

describe('identityFromToken', () => {
  it('reads user and known roles from the token', () => {
    expect(identityFromToken(jwt({ preferred_username: 'rm-anil', roles: ['rm', 'SERVICE', 'APPROVER'] })))
      .toEqual({ user: 'rm-anil', roles: ['RM', 'APPROVER'] });
    expect(identityFromToken(jwt({ sub: 'abc' }))).toEqual({ user: 'abc', roles: [] });
  });

  it('returns null for a malformed token or one without a user', () => {
    expect(identityFromToken('not-a-jwt')).toBeNull();
    expect(identityFromToken(jwt({ roles: ['RM'] }))).toBeNull();
  });

  it('makes the signed-in identity the current one', () => {
    setAccessToken(jwt({ preferred_username: 'wrm-meera', roles: ['APPROVER'] }));
    expect(getIdentity()).toEqual({ user: 'wrm-meera', roles: ['APPROVER'] });
    setAccessToken(null);
    expect(getIdentity().user).not.toBe('wrm-meera');
  });
});

describe('loadAuthConfig', () => {
  it('uses the server config when it names an authority', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ...CFG, scope: '' }) });
    expect(await loadAuthConfig(fetchImpl as unknown as typeof fetch))
      .toEqual({ ...CFG, scope: 'openid profile' });
  });

  it('falls back to development mode', async () => {
    const empty = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ authority: '', clientId: '' }) });
    const missing = vi.fn().mockResolvedValue({ ok: false });
    const broken = vi.fn().mockRejectedValue(new Error('offline'));
    for (const f of [empty, missing, broken]) {
      expect(await loadAuthConfig(f as unknown as typeof fetch)).toBeNull();
    }
  });
});

describe('startAuth', () => {
  const history = { replaceState: vi.fn() } as unknown as History;

  beforeEach(() => {
    vi.clearAllMocks();
    settingsSeen.length = 0;
  });
  afterEach(() => setAccessToken(null));

  it('renders straight away in development mode', async () => {
    expect(await startAuth(null)).toBe(true);
  });

  it('sends a signed-out user to the identity provider, keeping the page to return to', async () => {
    manager.getUser.mockResolvedValue(null);
    expect(await startAuth(CFG, loc('/cp/CP-1001', '?tab=x'), history)).toBe(false);
    expect(manager.signinRedirect).toHaveBeenCalledWith({ state: '/cp/CP-1001?tab=x' });
    expect(settingsSeen[0]).toMatchObject({ client_id: 'covenant-ui', response_type: 'code',
      redirect_uri: 'http://localhost:8080/auth/callback' });
  });

  it('completes the callback, stores the token in memory and returns to the original page', async () => {
    const token = jwt({ preferred_username: 'rm-anil', roles: ['RM'] });
    manager.signinRedirectCallback.mockResolvedValue({ access_token: token, expired: false, state: '/reports' });
    expect(await startAuth(CFG, loc('/auth/callback'), history)).toBe(true);
    expect(getAccessToken()).toBe(token);
    expect(history.replaceState).toHaveBeenCalledWith(null, '', '/reports');
    expect(signedIn()).toBe(true);
  });

  it('ignores a return address that is not a local path', async () => {
    manager.signinRedirectCallback.mockResolvedValue({ access_token: 't', expired: false,
      state: 'https://evil.example' });
    await startAuth(CFG, loc('/auth/callback'), history);
    expect(history.replaceState).toHaveBeenCalledWith(null, '', '/');
  });

  it('keeps an existing session and renews through the manager events', async () => {
    const token = jwt({ preferred_username: 'admin-1', roles: ['ADMIN'] });
    manager.getUser.mockResolvedValue({ access_token: token, expired: false });
    expect(await startAuth(CFG, loc('/portfolio'), history)).toBe(true);
    expect(getAccessToken()).toBe(token);

    const loaded = manager.events.addUserLoaded.mock.calls[0][0] as (u: unknown) => void;
    loaded({ access_token: 'renewed', expired: false });
    expect(getAccessToken()).toBe('renewed');
    const unloaded = manager.events.addUserUnloaded.mock.calls[0][0] as () => void;
    unloaded();
    expect(getAccessToken()).toBeNull();
  });

  it('signs out at the identity provider', async () => {
    manager.getUser.mockResolvedValue({ access_token: 't', expired: false });
    await startAuth(CFG, loc('/portfolio'), history);
    await signOut();
    expect(manager.signoutRedirect).toHaveBeenCalled();
    expect(getAccessToken()).toBeNull();
  });
});
