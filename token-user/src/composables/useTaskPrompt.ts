import { h, watch } from 'vue';
import { closeDialog, showDialog } from 'vant';
import { contentApi, translationPending } from '@/api/content';
import { sanitizeContentHtml } from '@/utils/content-html';
import { contentJump } from '@/utils/content-jump';
import { i18n } from '@/locales';
import { TOKEN_KEY } from '@/api';
import router from '@/router';
import { useSession } from '@/stores/session';
import pinia from '@/stores';
import '@/styles/task-prompt.css';

let pending: Promise<boolean> | undefined;
/** 有效行为限制由后台解除；用户只能阅读提示，确认按钮不会放行投资或提款。 */
export async function showTaskPrompt(scene: 'INVEST' | 'WITHDRAW', signal?: AbortSignal): Promise<boolean> {
  if (pending || signal?.aborted) return false;
  const token = sessionStorage.getItem(TOKEN_KEY) || localStorage.getItem(TOKEN_KEY);
  const location = router.currentRoute.value.fullPath;
  const controller = new AbortController();
  let dialogOpen = false;
  let cancelDialog: (() => void) | undefined;
  const cancel = () => { controller.abort(); if (dialogOpen) closeDialog(); cancelDialog?.(); };
  signal?.addEventListener('abort', cancel, { once: true });
  const stopSession = watch(() => useSession(pinia).token, changed => { if (changed !== token) cancel(); });
  const stopLanguage = watch(i18n.global.locale, cancel);
  const stopNavigation = router.afterEach(to => {
    if (to.fullPath === location) return;
    cancel();
  });
  const current = () => !controller.signal.aborted && token && token === (sessionStorage.getItem(TOKEN_KEY) || localStorage.getItem(TOKEN_KEY));
  pending = (async () => {
    let restricted = false;
    for (let page = 1; page <= 100; page++) {
      if (!current()) return false;
      const result = await contentApi.scene(scene, controller.signal, page);
      if (!current()) return false;
      if (!result.records.length) return !restricted;
      restricted = true;
      for (let index = 0; index < result.records.length; index++) {
        const content = result.records[index]!;
        const more = (page - 1) * 20 + index + 1 < result.total;
        const jump = contentJump(content, router);
        try {
          dialogOpen = true;
          // Vant 的 closeDialog 只隐藏弹窗，不结算 showDialog Promise；主动取消必须同时释放等待。
          await Promise.race([showDialog({
            title: content.title || i18n.global.t('user.v17.task.title'),
            message: () => h('div', { class: 'task-prompt-body' }, [
              translationPending(content) || !content.bodyHtml
                ? h('p', i18n.global.t('user.content.translationWaiting'))
                : h('div', { class: 'rich-content task-prompt-content', innerHTML: sanitizeContentHtml(content.bodyHtml) }),
              ...(jump ? [h('a', { class: 'task-prompt-link', href: jump.href, target: jump.target, rel: 'noopener noreferrer', onClick: (event: MouseEvent) => {
                if (jump.path && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey && event.button === 0) { event.preventDefault();void router.push(jump.path); }
              } }, i18n.global.t('user.content.openLink'))] : []),
            ]),
            confirmButtonText: i18n.global.t(more ? 'user.v17.task.next' : 'user.v9.content.gotIt'),
            cancelButtonText: i18n.global.t('user.v17.task.close'),
            showCancelButton: more,
            className: 'ui-dialog task-prompt-dialog',
            theme: 'round-button',
            closeOnClickOverlay: false,
          }), new Promise<void>((resolve) => { cancelDialog = resolve; })]);
        } catch { return false; }
        finally { dialogOpen = false; cancelDialog = undefined; }
        if (!current()) return false;
        if (!translationPending(content) && content.bodyHtml) {
          await contentApi.read(content.id, controller.signal);
          if (!current()) return false;
          window.dispatchEvent(new Event('token-user-unread-changed'));
        }
        if (!more) return false;
      }
    }
    return false;
  })();
  try { return await pending; }
  catch (cause) { if (controller.signal.aborted) return false; throw cause; }
  finally { stopNavigation(); stopSession(); stopLanguage(); signal?.removeEventListener('abort', cancel); controller.abort(); pending = undefined; }
}
