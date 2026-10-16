import { describe, expect, it, vi } from 'vitest';
import { migrateUpdateCheckPreference } from './update-preference-migration';

describe('更新检查旧偏好一次性迁移', () => {
  it('仅打开旧检查开关，保留其他偏好与会话，之后主动关闭不覆盖', async () => {
    const values = new Map<string, unknown>([['preferences', { app: { enableCheckUpdates: false, locale: 'en-US' }, theme: { mode: 'dark' } }], ['access-token', 'retained']]);
    const cache = { getItem: async <T>(key: string) => (values.get(key) ?? null) as null | T, setItem: async (key: string, value: unknown) => { values.set(key, value); } };
    await migrateUpdateCheckPreference(cache);
    expect(values.get('preferences')).toEqual({ app: { enableCheckUpdates: true, locale: 'en-US' }, theme: { mode: 'dark' } });
    expect(values.get('access-token')).toBe('retained');
    (values.get('preferences') as any).app.enableCheckUpdates = false;
    await migrateUpdateCheckPreference(cache);
    expect((values.get('preferences') as any).app.enableCheckUpdates).toBe(false);
  });
  it('偏好保存失败时不会提前写入迁移完成标志', async () => {
    const cache = { getItem: async <T>() => null as null | T, setItem: vi.fn().mockRejectedValue(new Error('unavailable')) };
    await expect(migrateUpdateCheckPreference(cache)).rejects.toThrow('unavailable');
    expect(cache.setItem).toHaveBeenCalledTimes(1);
    expect(cache.setItem.mock.calls[0]![0]).toBe('preferences');
  });
});
