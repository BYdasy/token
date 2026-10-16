import { computed, onMounted, ref, toValue } from "vue";
import type { MaybeRefOrGetter } from "vue";
import { api } from "@/api";
import type { Currency } from "@/api/types";
import { useTask } from "./useTask";

/** 资金操作进入页面时读取当前通道，后台仍在每次提交时作最终校验。 */
export function useCurrencies(
  operation: "DEPOSIT" | "WITHDRAW" | "ADDRESS",
  wallet: MaybeRefOrGetter<string>,
) {
  const records = ref<Currency[]>([]);
  const task = useTask();
  async function refresh() {
    await task.run(async () => {
      records.value = await api.currencies();
    }, false);
  }
  const availableChannels = computed(() =>
    records.value.filter(
      (item) =>
        (operation !== "DEPOSIT" || item.depositEnabled) &&
        (operation === "DEPOSIT" || item.withdrawEnabled),
    ),
  );
  const channels = computed(() => availableChannels.value.filter(
    item => item.supportAccountTypes.split(",").map(value => value.trim()).includes(toValue(wallet)),
  ));
  onMounted(() => {
    void refresh();
  });
  return { channels, availableChannels, task, refresh };
}
