// 与 TranslationServiceImpl 的运行时参数协议一致；不执行插值，不改写译文。
const literals = /\{'[^'\r\n]*'}/g;
const parameters = /(?<!\{)(?:\{\{[\p{L}\p{N}_$]+\}\}|\{[\p{L}\p{N}_$]+\})(?!\})/gu;

export function translationParameters(value) {
  const text = value.replace(literals, '');
  const found = [...new Set([...text.matchAll(parameters)].map(match => match[0]))].sort();
  const remaining = text.replace(parameters, '');
  return { parameters: found, valid: !/[{}]/.test(remaining) };
}
