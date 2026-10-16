import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';
const mocks = vi.hoisted(() => ({access:null as any,user:null as any,permissions:['30100'],get:vi.fn(),play:vi.fn()}));
vi.mock('@vben/stores',()=>({useAccessStore:()=>mocks.access,useUserStore:()=>mocks.user}));
vi.mock('@vben/access',()=>({useAccess:()=>({hasAccessByCodes:(codes:string[])=>codes.some(code=>mocks.permissions.includes(code)),hasAccessByRoles:()=>false})}));
vi.mock('#/api/request',()=>({requestClient:{get:mocks.get}}));
import { useFinanceSound } from './useFinanceSound';
const apps:ReturnType<typeof createApp>[]=[];
let events:Record<string,{id:number;eventKey:string}[]>;
let policy:any, scope:string;
function mount(){let sound!:ReturnType<typeof useFinanceSound>;const app=createApp({setup:()=>{sound=useFinanceSound();return()=>h('div');}});apps.push(app);app.mount(document.createElement('div'));return sound;}
async function settle(){for(let i=0;i<30;i++)await Promise.resolve();await nextTick();}
async function advance(ms=5000){await vi.advanceTimersByTimeAsync(ms);await settle();}
function add(resource='deposits',count=1){for(let i=0;i<count;i++){const id=events[resource]!.length+1;events[resource]!.push({id,eventKey:`${resource}:${id}`});}}
async function ready(){const sound=mount();await settle();await sound.unlock();await settle();mocks.play.mockClear();return sound;}
beforeEach(()=>{
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-10-11T00:00:00Z'));vi.stubEnv('VITE_APP_NAMESPACE','finance-test');localStorage.clear();mocks.get.mockReset();mocks.play.mockReset();
  mocks.permissions=['30100'];mocks.access=reactive({accessToken:'session-a'});mocks.user=reactive({userInfo:{username:'alice'}});
  events={deposits:[],withdrawals:[]};add();scope='scope-a';policy={version:1,intervalSeconds:5,deposits:{enabled:true},withdrawals:{enabled:false}};
  mocks.get.mockImplementation(async(path:string,options:any)=>{
    if(path.endsWith('/runtime'))return options.params.version===policy.version?{unchanged:true,version:policy.version}:structuredClone(policy);
    const rows=events[path.includes('/deposits/')?'deposits':'withdrawals']!;
    const initialized=options.params.afterId!==undefined&&options.params.scopeVersion===scope;
    return {records:initialized?rows.filter(row=>row.id>options.params.afterId):[],cursor:rows.at(-1)?.id??0,scopeVersion:scope,hasMore:false};
  });
  const locks=new Set<string>();Object.defineProperty(navigator,'locks',{configurable:true,value:{request:async(name:string,_options:any,action:(lock:object|null)=>Promise<void>)=>{if(locks.has(name))return action(null);locks.add(name);try{return await action({});}finally{locks.delete(name);}}}});
  vi.stubGlobal('AudioContext',class{state='running';currentTime=0;destination={};resume=async()=>{};createOscillator=()=>({frequency:{setValueAtTime:vi.fn()},connect:vi.fn(),disconnect:vi.fn(),start:mocks.play,stop:vi.fn(),onended:null});createGain=()=>({gain:{setValueAtTime:vi.fn(),exponentialRampToValueAtTime:vi.fn()},connect:vi.fn(),disconnect:vi.fn()});});
});
afterEach(()=>{apps.splice(0).forEach(app=>app.unmount());vi.unstubAllGlobals();vi.unstubAllEnvs();vi.useRealTimers();});
describe('新订单按可配置间隔合并声音',()=>{
  it('首次历史静默，5秒窗口多单只响一次',async()=>{
    await ready();add('deposits',4);await advance();expect(mocks.play).not.toHaveBeenCalled();add('deposits',2);await advance();expect(mocks.play).toHaveBeenCalledTimes(1);await advance(15000);expect(mocks.play).toHaveBeenCalledTimes(1);
    expect(mocks.get.mock.calls.every(([,options])=>options.headers['X-Background-Request']==='true')).toBe(true);expect(mocks.get.mock.calls.some(([path])=>path.includes('/withdrawals/'))).toBe(false);
  });
  it('15秒配置生效，后续新窗口仍能提醒',async()=>{
    policy.intervalSeconds=15;await ready();add();await advance(15000);expect(mocks.play).not.toHaveBeenCalled();await advance();expect(mocks.play).toHaveBeenCalledTimes(1);add();await advance(20000);expect(mocks.play).toHaveBeenCalledTimes(2);
  });
  it('充值提款各自合并，两个提示音不互相覆盖',async()=>{
    mocks.permissions=['30100','30200'];policy.withdrawals.enabled=true;await ready();add('deposits',3);add('withdrawals',3);await advance(10000);expect(mocks.play).toHaveBeenCalledTimes(1);await advance(700);expect(mocks.play).toHaveBeenCalledTimes(2);
  });
  it('多个标签共享事件基线和播放锁',async()=>{
    await ready();mount();await settle();window.dispatchEvent(new Event('token-finance-audio-ready'));await settle();add();await advance(15000);expect(mocks.play).toHaveBeenCalledTimes(1);
  });
  it('停用停止查询，重新启用不补播历史',async()=>{
    await ready();policy.version++;policy.deposits.enabled=false;window.dispatchEvent(new Event('token-finance-settings-changed'));await settle();mocks.get.mockClear();add();await advance(10000);expect(mocks.get).not.toHaveBeenCalled();policy.version++;policy.deposits.enabled=true;window.dispatchEvent(new Event('token-finance-settings-changed'));await settle();await advance(10000);expect(mocks.play).not.toHaveBeenCalled();
  });
  it('代理范围变化新建静默基线',async()=>{
    await ready();scope='scope-b';add();await advance(15000);expect(mocks.play).not.toHaveBeenCalled();add();await advance(10000);expect(mocks.play).toHaveBeenCalledTimes(1);
  });
  it('代理范围变化取消旧范围已经排队的提醒',async()=>{
    policy.intervalSeconds=15;await ready();add();await advance();scope='scope-b';await advance(25000);expect(mocks.play).not.toHaveBeenCalled();
  });
  it('最大3600秒设置在截止后仍能播放',async()=>{
    policy.intervalSeconds=3600;await ready();add();await advance(3605000);expect(mocks.play).toHaveBeenCalledTimes(1);
  });
  it('退出取消待播与迟到请求',async()=>{
    await ready();let resolve!:(value:any)=>void;mocks.get.mockImplementationOnce(()=>new Promise(done=>{resolve=done;}));await advance();mocks.access.accessToken=null;await nextTick();resolve({records:[{id:2,eventKey:'late'}],cursor:2,scopeVersion:scope,hasMore:false});await settle();await advance(10000);expect(mocks.play).not.toHaveBeenCalled();expect(localStorage.getItem('finance-test:finance-sound:v3:alice:pending')).toBeNull();
  });
  it('权限拒绝停止重试，主动恢复再检查',async()=>{
    const sound=await ready();mocks.get.mockRejectedValueOnce({response:{status:403}});await advance();const count=mocks.get.mock.calls.length;await advance(10000);expect(mocks.get.mock.calls.length).toBe(count);await sound.unlock();await settle();expect(mocks.get.mock.calls.length).toBeGreaterThan(count);
  });
});
