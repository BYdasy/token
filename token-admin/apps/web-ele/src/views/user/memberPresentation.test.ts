import { describe, expect, it } from 'vitest';
import { memberLanguageName } from './memberPresentation';

describe('用户列表中文语言名称', () => {
  it('旧接口或历史中文列误存英文时仍显示中文', () => {
    expect(memberLanguageName('ar', 'Arabic')).toBe('阿拉伯语');
    expect(memberLanguageName('en', 'English')).toBe('英语');
    expect(memberLanguageName('ar', 'العربية')).toBe('阿拉伯语');
  });
  it('保留人工中文名称以及地区和书写体系', () => {
    expect(memberLanguageName('en_US', '美式英语')).toBe('美式英语');
    expect(memberLanguageName('pt_BR', 'Portuguese')).toContain('巴西');
    expect(memberLanguageName('ar_SA', 'Arabic')).toContain('沙特阿拉伯');
    expect(memberLanguageName('zh_Hant', '')).toContain('繁体');
  });
  it('空值与未知历史语言不导致整张列表渲染失败', () => {
    expect(memberLanguageName(null, null)).toBe('—');
    expect(memberLanguageName('unknown_invalid', 'Custom')).toBe('Custom');
    expect(memberLanguageName('', '自定义中文')).toBe('自定义中文');
  });
});
