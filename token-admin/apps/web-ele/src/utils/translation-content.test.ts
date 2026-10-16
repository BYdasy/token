import { describe, expect, it } from 'vitest';
import { hasTranslationContent } from './translation-content';
describe('富文本正文内容', () => {
  it('合法图片可以单独构成正文', () => {
    expect(hasTranslationContent('<p><img src="/api/material/image/mat_example.webp" alt=""></p>', true, true)).toBe(true);
    expect(hasTranslationContent('<img src="https://example.com/image.webp">', true, true)).toBe(true);
  });
  it('空标签、空白和脚本图片不能构成正文', () => {
    for (const html of ['<p><br></p>', '<p>&nbsp;\u200B</p>', '<img src="javascript:alert(1)">', '<img src="//example.com/image.webp">', '<script>alert(1)</script>'])
      expect(hasTranslationContent(html, true, true)).toBe(false);
  });
});
