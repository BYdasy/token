type AttemptStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;
const inMemory = new Map<string,string>();
/** 只保存随机请求号，账号+活动隔离；未知结果重试原号，不保存凭据或资金参数。 */
export function activityAttempt(storage: AttemptStorage | undefined, memberId: number, activityId: number, createId = () => crypto.randomUUID()) {
  const key = `token-activity-attempt:${memberId}:${activityId}`;
  let id = inMemory.get(key);
  try { id ||= storage?.getItem(key) || undefined; } catch { /* 浏览器禁用存储时仍使用内存去重 */ }
  if (!id || !/^[A-Za-z0-9_-]{8,80}$/.test(id)) id = createId();
  inMemory.set(key,id);
  try { storage?.setItem(key,id); } catch { /* 内存中保持同一请求号 */ }
  return {
    requestId:id,
    complete() {
      if (inMemory.get(key) === id) inMemory.delete(key);
      try { if (storage?.getItem(key) === id) storage?.removeItem(key); } catch { /* 存储未写入时无需清理 */ }
    },
  };
}
