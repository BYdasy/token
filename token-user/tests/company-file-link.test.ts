import { describe, expect, it } from 'vitest';
import { companyFileLink } from '../src/utils/company-file-link';

describe('公司文件外链', () => {
  it('允许 HTTPS 网页和 Word 文档链接，不推断文件类型或下载内容', () => {
    expect(companyFileLink('https://example.org/about')).toBe('https://example.org/about');
    expect(companyFileLink('https://example.org/company.docx?version=2')).toBe('https://example.org/company.docx?version=2');
  });
  it.each(['javascript:alert(1)', 'data:text/html,hello', 'file:///a', '//evil.test', '/api/private', 'https://user:pass@example.org', 'https://example.org\\evil', 'https://example.org/\r\na'])('阻止危险或含凭据链接：%s', value => {
    expect(companyFileLink(value)).toBe('');
  });
});
