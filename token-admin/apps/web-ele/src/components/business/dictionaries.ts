import { ref } from 'vue';
import { useI18n } from '@vben/locales';
import {
  fetchComputeDictionaries,
  type ComputeDictionaries,
} from '#/api/compute';
export function useDictionaries() {
  const { t } = useI18n();
  const dictionaries = ref<ComputeDictionaries>({ scope: { global: false } });
  const dictionaryLoading = ref(false);
  async function loadDictionaries() {
    dictionaryLoading.value = true;
    try {
      dictionaries.value = await fetchComputeDictionaries();
    } finally {
      dictionaryLoading.value = false;
    }
  }
  const options = (
    name: 'groups' | 'agents' | 'levels' | 'adminAccounts' | 'products',
  ) =>
    (dictionaries.value[name] || [])
      .filter(
        (row) =>
          name !== 'adminAccounts' ||
          (Number(row.uid ?? row.id) !== 1 &&
            String(row.username).toLowerCase() !== 'admin'),
      )
      .map((row) => ({
        value: Number(row.id ?? row.uid),
        label: String(row.nameKey ? t(row.nameKey) : row.name ?? row.username ?? row.userName ?? '—'),
      }));
  return { dictionaries, dictionaryLoading, loadDictionaries, options };
}
