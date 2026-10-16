import { describe, expect, it } from 'vitest';
import { chineseLanguageLabel, chineseLanguageOption, englishLanguageLabel, orderedBusinessLanguages } from './businessLanguages';

describe('业务翻译语言顺序', () => {
  it('中文、英文固定在前，其余保留接口按后台配置提供的顺序', () => {
    const rows = [
      { label: 'Français', value: 'fr' }, { label: '中文', value: 'zh' },
      { label: '日本語', value: 'ja' }, { label: 'English', value: 'en' },
      { label: 'العربية', value: 'ar' },
    ];
    expect(orderedBusinessLanguages(rows).map((row) => row.value)).toEqual(['zh', 'en', 'fr', 'ja', 'ar']);
    expect(rows[0]?.value).toBe('fr');
  });
  it('语言初始化未返回基础语言时仍提供中文、英文编辑项', () => {
    expect(orderedBusinessLanguages([{ label: 'Français', value: 'fr' }]).map((row) => row.value))
      .toEqual(['zh', 'en', 'fr']);
  });
  it('地区语言统一大小写和分隔符，完整代码仍保留且别名不重复', () => {
    expect(orderedBusinessLanguages([
      { label: 'Português', value: 'pt_BR' }, { label: 'Português', value: 'pt-br' },
      { label: 'English', value: 'EN' },
    ]).map(row => row.value)).toEqual(['zh', 'en', 'pt-br']);
  });
});

describe('管理端语言名称', () => {
  it('按中文显示并保留原文、英文及代码搜索', () => {
    const french = chineseLanguageOption({ value: 'fr', label: 'Français' });
    expect(french.label).toBe('法语');
    expect(french.searchText).toContain('français');
    expect(french.searchText).toContain('french');
    expect(french.searchText).toContain('法语');
  });
  it('优先已有中文配置并保留未知语言代码', () => {
    expect(chineseLanguageOption({ value: 'en', label: 'English', chineseName: '英文' }).label).toBe('英文');
    expect(chineseLanguageOption({ value: 'invalid_code', label: 'Custom' }).label).toBe('Custom');
  });
  it('历史name是中文而chineseName误存英文时统一显示中文', () => {
    const arabic = chineseLanguageOption({ value: 'ar', label: 'العربية', name: '阿拉伯语', chineseName: 'Arabic' });
    expect(arabic.label).toBe('阿拉伯语');
    expect(arabic.searchText).toContain('arabic');
    expect(chineseLanguageOption({ value: 'pt_BR', label: 'Portuguese', chineseName: 'Portuguese' }).label).toContain('巴西');
  });
  it('语言配置中英文列按实际语义回显，西班牙语本地名不能冒充英文', () => {
    expect(chineseLanguageLabel('es', 'Español', '西班牙语')).toBe('西班牙语');
    expect(englishLanguageLabel('es', '西班牙语')).toBe('Spanish');
    expect(englishLanguageLabel('ar', '阿拉伯语')).toBe('Arabic');
    expect(englishLanguageLabel('pt-BR', 'Brazilian Portuguese')).toBe('Brazilian Portuguese');
  });
});
