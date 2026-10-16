/** 内容与链接采用白名单，服务端清洗后仍在浏览器渲染前再次处理。 */
export function safeContentLink(value?: string | null): string | undefined {
  const input = String(value || "").trim();
  if (!input || /[\u0000-\u001f\u007f]/.test(input)) return undefined;
  try {
    const parsed = new URL(input, window.location.href);
    return ["https:", "http:"].includes(parsed.protocol) ? parsed.href : undefined;
  } catch {
    return undefined;
  }
}
export function sanitizeContentHtml(value?: string | null): string {
  const parsed = new DOMParser().parseFromString(String(value || ""), "text/html");
  parsed
    .querySelectorAll(
      "script,style,iframe,object,embed,link,meta,form,input,button,svg,math,template",
    )
    .forEach((node) => node.remove());
  const tags = new Set([
    "P",
    "DIV",
    "SPAN",
    "BR",
    "B",
    "STRONG",
    "I",
    "EM",
    "U",
    "S",
    "DEL",
    "H1",
    "H2",
    "H3",
    "H4",
    "H5",
    "H6",
    "UL",
    "OL",
    "LI",
    "BLOCKQUOTE",
    "PRE",
    "CODE",
    "HR",
    "A",
    "IMG",
    "TABLE",
    "THEAD",
    "TBODY",
    "TR",
    "TH",
    "TD",
  ]);
  for (const element of [...parsed.body.querySelectorAll("*")]) {
    if (!tags.has(element.tagName)) {
      element.replaceWith(...element.childNodes);
      continue;
    }
    for (const attribute of [...element.attributes]) {
      const name = attribute.name.toLowerCase();
      if (element.tagName === "A" && name === "href") {
        const safe = safeContentLink(attribute.value);
        if (safe) element.setAttribute("href", safe);
        else element.removeAttribute(name);
      } else if (element.tagName === "IMG" && name === "src") {
        const safe = safeContentLink(attribute.value);
        if (safe) element.setAttribute("src", safe);
        else element.removeAttribute(name);
      } else if (!["alt", "title"].includes(name)) element.removeAttribute(name);
    }
    if (element.tagName === "A") {
      element.setAttribute("target", "_blank");
      element.setAttribute("rel", "noopener noreferrer");
    }
    if (element.tagName === "IMG") {
      element.setAttribute("loading", "lazy");
      element.setAttribute("referrerpolicy", "no-referrer");
    }
  }
  return parsed.body.innerHTML;
}

/** 卡片摘要只展示清洗后的纯文本，完整 HTML 留在详情页。 */
export function contentText(value?: string | null): string {
  return new DOMParser().parseFromString(sanitizeContentHtml(value), "text/html").body.textContent || "";
}
