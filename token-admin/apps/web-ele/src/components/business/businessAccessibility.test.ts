import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive, ref } from 'vue';

const state = vi.hoisted(() => ({ save: vi.fn().mockResolvedValue(undefined) }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock('@vben/stores', () => ({ useUserStore: () => ({ userInfo: { uid: 7 } }) }));
vi.mock('#/api/table-columns', () => ({ saveTableColumnPreference: state.save }));
vi.mock('./shared', () => ({ useCompute: () => ({ c: (key: string) => key, enumLabel: String }) }));
import BusinessSearchFields from './BusinessSearchFields.vue';
import BusinessForm from './BusinessForm.vue';
import BusinessTableToolbar from './BusinessTableToolbar.vue';

const cleanup: Array<() => void> = [];
async function settle() { for (let i = 0; i < 7; i++) { await Promise.resolve(); await nextTick(); } }
async function mount(render: () => ReturnType<typeof h>) {
  const host = document.createElement('div'); document.body.append(host);
  const app = createApp({ render }); app.mount(host);
  cleanup.push(() => { app.unmount(); host.remove(); }); await settle(); return host;
}
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); state.save.mockClear(); });

describe('共享业务控件名称及操作保持', () => {
  it('已填写搜索框和国家下拉仍有稳定名称，Enter沿用单次search事件', async () => {
    const model = reactive({ username: 'saved-user', country: 'US' });
    const search = vi.fn();
    const host = await mount(() => h(BusinessSearchFields, { model, fields: [
      { key: 'username' },
      { key: 'country', labelKey: 'v8.registrationCountry', options: [{ value: 'US', label: '美国', searchKeywords: 'United States US' }] },
    ], onSearch: search }));
    const input = host.querySelector<HTMLInputElement>('input[aria-label="field.username"]')!;
    expect(input.value).toBe('saved-user');
    expect(host.querySelector('[role=combobox][aria-label="v8.registrationCountry"]')).not.toBeNull();
    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
    expect(search).toHaveBeenCalledTimes(1);
    expect(model).toEqual({ username: 'saved-user', country: 'US' });
  });

  it('两份表单帮助ID不冲突，控件保留字段名及原模型绑定', async () => {
    const model = reactive({ name: '示例', amount: '1.234', enabled: true });
    const form = ref<InstanceType<typeof BusinessForm>>();
    const fields = [
      { key: 'name', required: true, hint: 'nameHelp' },
      { key: 'amount', kind: 'decimal' as const, required: true, precision: 2, hint: 'amountHelp' },
      { key: 'enabled', kind: 'boolean' as const },
    ];
    const host = await mount(() => h('div', [h(BusinessForm, { model, fields, ref: form }), h(BusinessForm, { model, fields })]));
    const helpIds = [...host.querySelectorAll('.compute-help')].map(element => element.id);
    expect(new Set(helpIds).size).toBe(4);
    for (const group of host.querySelectorAll('[role=group][aria-describedby]')) {
      const description = document.getElementById(group.getAttribute('aria-describedby')!);
      expect(description?.textContent).toMatch(/^hint\./);
    }
    expect(host.querySelectorAll('input[aria-label="field.name"]')).toHaveLength(2);
    expect(host.querySelectorAll('input[role=switch][aria-label="field.enabled"]')).toHaveLength(2);
    const amount = host.querySelector<HTMLInputElement>('input[aria-label="field.amount"]')!;
    expect(amount.value).toBe('1.234');
    amount.value = '2.25';
    amount.dispatchEvent(new Event('input', { bubbles: true })); await settle();
    expect(model.amount).toBe('2.25');
    expect([...host.querySelectorAll<HTMLInputElement>('input[aria-label="field.amount"]')].map(input => input.value)).toEqual(['2.25', '2.25']);
  });

  it('栏位移动按钮包含对应列名，固定列保留且保存原顺序协议', async () => {
    const update = vi.fn();
    const options = [{ key: 'user', label: '用户信息', required: true }, { key: 'email', label: '邮箱' }, { key: 'phone', label: '电话' }];
    const host = await mount(() => h(BusinessTableToolbar, { enabled: true, storageKey: 'user:members:v8', rows: [], columnOptions: options, columns: ['user', 'email', 'phone'], 'onUpdate:columns': update }));
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === 'admin.table.columns')!.click();
    await settle();
    const dialog = document.querySelector('.column-dialog')!;
    expect(dialog.querySelector('button[aria-label="admin.table.moveUp · 电话"]')).not.toBeNull();
    expect([...dialog.querySelectorAll('.el-transfer__button')].map(button => button.textContent?.trim())).toEqual(['admin.table.availableColumns', 'admin.table.selectedColumns']);
    const required = [...dialog.querySelectorAll('.el-transfer-panel__item')].find(item => item.textContent?.includes('用户信息'))!;
    expect(required.querySelector<HTMLInputElement>('input')!.disabled).toBe(true);
    dialog.querySelector<HTMLButtonElement>('button[aria-label="admin.table.moveUp · 电话"]')!.click();
    await settle();
    [...dialog.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button')].find(button => button.textContent?.trim() === 'admin.common.save')!.click();
    await settle();
    expect(state.save).toHaveBeenCalledWith('user:members:v8', ['user', 'phone', 'email']);
    expect(update).toHaveBeenCalledWith(['user', 'phone', 'email']);
  });
});
