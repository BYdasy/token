/** 空标签与不可见占位不算正文；富文本正文仍保留原有合法图片内容能力。 */
export function hasTranslationContent(
  value?: null | string,
  html = false,
  allowImages = false,
) {
  let text = String(value ?? '');
  if (html) {
    const document = new DOMParser().parseFromString(text, 'text/html');
    document
      .querySelectorAll('script,style,iframe,object,embed,link,meta')
      .forEach((node) => node.remove());
    if (
      allowImages &&
      [...document.querySelectorAll('img[src]')].some((node) => {
        const source = (node.getAttribute('src') || '').trim();
        return (
          (source.startsWith('/') && !source.startsWith('//')) ||
          /^https?:\/\//i.test(source)
        );
      })
    )
      return true;
    text = document.body.textContent || '';
  }
  return text.replaceAll(/[\s\u200B-\u200D\uFEFF]/gu, '').length > 0;
}
