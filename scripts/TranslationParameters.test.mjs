import assert from 'node:assert/strict';
import test from 'node:test';
import { translationParameters } from './TranslationParameters.mjs';

test('参数协议包含括号类型，顺序和重复次数不改变契约', () => {
  assert.deepEqual(translationParameters('{amount} / {currency} / {amount}'), {
    parameters: ['{amount}', '{currency}'], valid: true,
  });
  assert.notDeepEqual(translationParameters('{amount}'), translationParameters('{{amount}}'));
});

test('额外损坏的括号不会被已有合法参数掩盖', () => {
  for (const value of ['{amount', '{amount}}', '{{amount}', '{{{amount}}}', '{amount} / {amount', 'Success {unknown'])
    assert.equal(translationParameters(value).valid, false, value);
});

test('Vue 字面插值不作为参数，Unicode与数字参数可正常识别', () => {
  assert.deepEqual(translationParameters("{'@'} {'{'} {'}'} {'{amount}'} / {0} / {金额} / {$sum}"), {
    parameters: ['{$sum}', '{0}', '{金额}'], valid: true,
  });
});
