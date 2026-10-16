// 主动快捷入口与普通标签往返分开；同一次导航只触发一次查询。
let requested = 0;
let consumed = 0;
export function requestOnlineMembers() { return ++requested; }
export function consumeOnlineMembersRequest() {
  if (consumed === requested) return false;
  consumed = requested;
  return true;
}
export function cancelOnlineMembersRequest(request: number) {
  if (request === requested) consumed = request;
}
