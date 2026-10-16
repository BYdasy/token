import assert from 'node:assert/strict';
import test from 'node:test';
import { activityCanPlay, translatedActivity, descriptionForLanguage, spinDuration, wheelTarget } from '../src/utils/activity-wheel.ts';

test('spin stops on the server-selected segment after additional full rotations', () => {
  for(let index=0;index<8;index++) {
    const target=wheelTarget(1267,index,8,10);
    assert.ok(target>1267+4*360);
    assert.equal((target+(index+.5)*45)%360,0);
  }
  assert.equal(spinDuration(undefined),6);assert.equal(spinDuration(60),30);assert.equal(spinDuration('invalid'),6);
});
test('multilingual explanation uses exact, base and English fallback', () => {
  const config={descriptionHtml:'中文',descriptionTranslations:{en:'English',fr:'Français','fr-ca':'Québécois'}};
  assert.equal(descriptionForLanguage(config,'fr_CA'),'Québécois');
  assert.equal(descriptionForLanguage(config,'fr-BE'),'Français');
  assert.equal(descriptionForLanguage(config,'de'),'English');
  assert.equal(descriptionForLanguage(config,'zh-CN'),'中文');
  assert.equal(descriptionForLanguage({descriptionHtml:'旧说明'},'en'),'旧说明');
});

test('wheel and chest use granted attempts even after old limits are exhausted', () => {
  const old={status:'OPEN',myTotalCount:99,myTodayCount:9,perUserTotalLimit:1,perUserDailyLimit:1,myWheel:{availableCount:1}};
  assert.equal(activityCanPlay({...old,type:'WHEEL'},true),true);
  assert.equal(activityCanPlay({...old,type:'CHEST'},true),true);
  assert.equal(activityCanPlay({...old,type:'RED_PACKET'},true),false);
  assert.equal(activityCanPlay({...old,type:'CHEST',myWheel:{availableCount:0}},true),false);
  assert.equal(activityCanPlay({...old,type:'WHEEL',status:'CONFIG_REQUIRED'},true),false);
  assert.equal(activityCanPlay({...old,type:'WHEEL',myWheel:undefined},false),true);
});
test('activity title summary and rich explanation use independent language fallback', () => {
  const activity={name:'旧标题',description:'旧描述',wheelConfig:{translations:{zh:{title:'中文',description:'说明',content:'<p>正文</p>'},en:{title:'Title',description:'Summary',content:'<p>Rules</p>'},fr:{title:'Titre',description:'',content:'<p>Règles</p>'}}}};
  assert.deepEqual(translatedActivity(activity,'fr-CA'),{title:'Titre',description:'Summary',content:'<p>Règles</p>'});
  assert.deepEqual(translatedActivity(activity,'de'),{title:'Title',description:'Summary',content:'<p>Rules</p>'});
  assert.equal(translatedActivity(activity,'zh-CN').title,'中文');
});
