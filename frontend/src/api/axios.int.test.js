import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import axios from 'axios';
import api from '@frontend/api/axios';

// Integration test for the API client: the real interceptors run, with the network swapped for a
// fake adapter so each test scripts the backend's responses.
const httpError = (config, status, data = {}) =>
  Object.assign(new Error(`Request failed with status code ${status}`), {
    config,
    response: { status, data, headers: {}, config },
    isAxiosError: true,
  });

const ok = (config, data = 'ok') => Promise.resolve({ data, status: 200, statusText: 'OK', headers: {}, config });

describe('api client (axios instance)', () => {
  let requests;
  let respond;

  beforeEach(() => {
    requests = [];
    api.defaults.adapter = (config) => {
      requests.push(config);
      return respond(config);
    };
  });

  afterEach(() => {
    vi.restoreAllMocks();
    delete api.defaults.headers.common.Authorization;
  });

  it('should send the stored access token as a Bearer header', async () => {
    localStorage.setItem('accessToken', 'token-abc');
    respond = (config) => ok(config);

    await api.get('/products');

    expect(requests[0].baseURL).toBe('/api');
    expect(requests[0].headers.Authorization).toBe('Bearer token-abc');
  });

  it('should refresh the token and retry once given a 401 on a protected endpoint', async () => {
    localStorage.setItem('accessToken', 'expired');
    const refresh = vi.spyOn(axios, 'post').mockResolvedValue({ data: { accessToken: 'fresh' } });
    respond = (config) =>
      config.headers.Authorization === 'Bearer fresh' ? ok(config, { id: 1 }) : Promise.reject(httpError(config, 401));

    const res = await api.get('/orders/1');

    expect(res.data).toEqual({ id: 1 });
    expect(refresh).toHaveBeenCalledWith('/api/auth/refresh', {}, { withCredentials: true });
    expect(localStorage.getItem('accessToken')).toBe('fresh');
    expect(requests).toHaveLength(2);
  });

  it('should refresh only once given several requests fail with 401 at the same time', async () => {
    localStorage.setItem('accessToken', 'expired');
    let resolveRefresh;
    const refresh = vi.spyOn(axios, 'post').mockReturnValue(
      new Promise((resolve) => {
        resolveRefresh = resolve;
      }),
    );
    respond = (config) =>
      config.headers.Authorization === 'Bearer fresh' ? ok(config, config.url) : Promise.reject(httpError(config, 401));

    const first = api.get('/cart');
    const second = api.get('/orders');
    await vi.waitFor(() => expect(refresh).toHaveBeenCalled());
    resolveRefresh({ data: { accessToken: 'fresh' } });

    const [a, b] = await Promise.all([first, second]);
    expect([a.data, b.data]).toEqual(['/cart', '/orders']);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('should reject queued requests too given the shared refresh fails', async () => {
    localStorage.setItem('accessToken', 'expired');
    let rejectRefresh;
    const refresh = vi.spyOn(axios, 'post').mockReturnValue(
      new Promise((_, reject) => {
        rejectRefresh = reject;
      }),
    );
    respond = (config) => Promise.reject(httpError(config, 401));

    const first = api.get('/cart');
    const second = api.get('/orders');
    await vi.waitFor(() => expect(refresh).toHaveBeenCalled());
    rejectRefresh(new Error('refresh failed'));

    const results = await Promise.allSettled([first, second]);
    expect(results.map((r) => r.status)).toEqual(['rejected', 'rejected']);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('should not try to refresh given a 401 from the login endpoint', async () => {
    const refresh = vi.spyOn(axios, 'post');
    respond = (config) => Promise.reject(httpError(config, 401, { message: 'Bad credentials' }));

    await expect(api.post('/auth/login', {})).rejects.toMatchObject({ response: { status: 401 } });
    expect(refresh).not.toHaveBeenCalled();
  });

  it('should clear the session and announce logout given the refresh itself fails', async () => {
    localStorage.setItem('accessToken', 'expired');
    localStorage.setItem('user', '{"name":"Test"}');
    vi.spyOn(axios, 'post').mockRejectedValue(new Error('refresh failed'));
    const onLogout = vi.fn();
    window.addEventListener('auth:logout', onLogout);
    respond = (config) => Promise.reject(httpError(config, 401));

    await expect(api.get('/cart')).rejects.toThrow('refresh failed');

    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('user')).toBeNull();
    expect(onLogout).toHaveBeenCalledOnce();
    window.removeEventListener('auth:logout', onLogout);
  });

  it('should pass non-401 errors straight through', async () => {
    respond = (config) => Promise.reject(httpError(config, 500));

    await expect(api.get('/products')).rejects.toMatchObject({ response: { status: 500 } });
    expect(requests).toHaveLength(1);
  });
});
