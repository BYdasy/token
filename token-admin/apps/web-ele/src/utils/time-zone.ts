/**
 * 将 IANA 时区转换为中文可读标签，保存值仍保持原始 IANA ID。
 */
export function formatChineseTimeZoneLabel(
  timeZone: string,
  offset?: string,
) {
  const normalizedOffset = offset
    ? offset === 'Z'
      ? 'UTC+00:00'
      : offset.startsWith('UTC')
        ? offset
        : `UTC${offset}`
    : '';

  let localizedName = '';
  try {
    localizedName =
      new Intl.DateTimeFormat('zh-CN', {
        timeZone,
        timeZoneName: 'long',
      })
        .formatToParts(Date.now())
        .find((part) => part.type === 'timeZoneName')?.value ?? '';
  } catch {
    localizedName = '';
  }

  const readableName = localizedName.replace(/^GMT/, 'UTC');
  if (readableName) {
    return `${readableName}${normalizedOffset ? `（${normalizedOffset}）` : ''}｜${timeZone}`;
  }
  return `${normalizedOffset ? `${normalizedOffset}｜` : ''}${timeZone} 时区`;
}
