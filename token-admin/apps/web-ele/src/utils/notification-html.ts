/**
 * 清理通知富文本中的可执行内容。
 * 通知允许常用排版标签，但不允许脚本、内嵌页面、事件属性和危险链接。
 */
export function sanitizeNotificationHtml(value?: null | string) {
  const html = String(value ?? '');
  if (!html || typeof DOMParser === 'undefined') return html;

  const document = new DOMParser().parseFromString(html, 'text/html');
  document
    .querySelectorAll('script,style,iframe,object,embed,link,meta,form')
    .forEach((node) => node.remove());

  document.body.querySelectorAll('*').forEach((element) => {
    for (const attribute of [...element.attributes]) {
      const name = attribute.name.toLowerCase();
      const value = attribute.value.trim().toLowerCase();
      if (
        name.startsWith('on') ||
        name === 'srcdoc' ||
        ((name === 'href' || name === 'src') &&
          /^(?:javascript|vbscript|data:text\/html)/.test(value))
      ) {
        element.removeAttribute(attribute.name);
      }
    }
  });

  return document.body.innerHTML;
}

