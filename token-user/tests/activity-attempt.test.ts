import assert from 'node:assert/strict';
import test from 'node:test';
import { activityAttempt } from '../src/utils/activity-attempt.ts';
function storage() {
 const data=new Map<string,string>();
 return {getItem:(key:string)=>data.get(key)||null,setItem:(key:string,value:string)=>{data.set(key,value);},removeItem:(key:string)=>{data.delete(key);}};
}
test('unknown outcome retries the same request and confirmed success starts a new one', () => {
 const saved=storage();let count=0;const create=()=>`activity-request-${++count}`;
 const first=activityAttempt(saved,801,17,create);
 assert.equal(activityAttempt(saved,801,17,create).requestId,first.requestId);
 assert.equal(count,1);
 first.complete();
 const next=activityAttempt(saved,801,17,create);
 assert.notEqual(next.requestId,first.requestId);
 first.complete();
 assert.equal(activityAttempt(saved,801,17,create).requestId,next.requestId);
});
test('accounts and activities never share pending requests', () => {
 const saved=storage();let count=0;const create=()=>`isolated-request-${++count}`;
 const ids=[activityAttempt(saved,802,17,create),activityAttempt(saved,803,17,create),activityAttempt(saved,802,18,create)].map(value=>value.requestId);
 assert.equal(new Set(ids).size,3);
});
test('blocked browser storage still retains a pending request in memory', () => {
 const blocked={getItem(){throw Error('blocked');},setItem(){throw Error('blocked');},removeItem(){throw Error('blocked');}};
 const first=activityAttempt(blocked,804,19,()=> 'memory-request-one');
 assert.equal(activityAttempt(blocked,804,19,()=> 'must-not-generate').requestId,first.requestId);
 first.complete();
 assert.equal(activityAttempt(blocked,804,19,()=> 'memory-request-two').requestId,'memory-request-two');
});

// Accessing window.sessionStorage itself may be blocked before getItem is available.
test('unavailable storage keeps the same in-memory request', () => {
 const first=activityAttempt(undefined,805,20,()=> 'getter-blocked-request');
 assert.equal(activityAttempt(undefined,805,20,()=> 'must-not-generate').requestId,first.requestId);
 first.complete();
});
