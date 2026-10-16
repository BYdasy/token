import { userSharePreviewUrl } from '../../user/memberAccess';

export interface SharePreviewSettings {
  userShareEntryUrl: string;
  userShareTitle: string;
  userShareDescription: string;
  userShareImage: string;
}

/** 对外复制普通用户站网址；独立预览仍读取服务端已保存文案，不把API地址当站点入口。 */
export function savedSharePreviewLinks(settings: SharePreviewSettings) {
  const preview = new URL(userSharePreviewUrl(settings.userShareEntryUrl));
  let version = 2166136261;
  for (const char of JSON.stringify(settings)) version = Math.imul(version ^ char.charCodeAt(0), 16777619);
  const versionText = (version >>> 0).toString(36);
  const entry = new URL(preview.searchParams.get('target') || '/', preview.origin);
  entry.searchParams.set('v', versionText);
  preview.searchParams.set('target', entry.pathname + entry.search);
  preview.searchParams.set('preview', '1');
  preview.searchParams.set('v', versionText);
  return { shareLink: entry.href, previewLink: preview.href };
}
