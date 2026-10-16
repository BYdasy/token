import { describe, expect, it } from 'vitest';
import { cancelOnlineMembersRequest, consumeOnlineMembersRequest, requestOnlineMembers } from './onlineMemberNavigation';

describe('顶部在线快捷导航', () => {
  it('首次挂载与导航完成事件只消费一次，普通标签往返没有新的重置', () => {
    requestOnlineMembers(); expect(consumeOnlineMembersRequest()).toBe(true);
    expect(consumeOnlineMembersRequest()).toBe(false);
    requestOnlineMembers(); expect(consumeOnlineMembersRequest()).toBe(true);
  });
  it('取消旧导航不能取消较新的点击，取消当前导航不遗留待执行重置', () => {
    const old = requestOnlineMembers(); requestOnlineMembers(); cancelOnlineMembersRequest(old);
    expect(consumeOnlineMembersRequest()).toBe(true);
    const current = requestOnlineMembers(); cancelOnlineMembersRequest(current);
    expect(consumeOnlineMembersRequest()).toBe(false);
  });
});
