<script setup lang="ts">
import { useSession } from "@/stores/session";
import { useProductCatalog } from "@/composables/useProductCatalog";
import { useVipAccount } from "@/composables/useVipAccount";
import PageState from "@/components/PageState.vue";
import VipAutoPanel from "@/components/VipAutoPanel.vue";

const session = useSession();
const { catalog, task, load, visibleError } = useProductCatalog();
const { presentation, error: accountError, loadAccount } = useVipAccount();
</script>

<template>
  <div class="ui-products-page ui-token-page">
    <p v-if="accountError" class="inline-error">
      {{ accountError }}
      <button class="text-button" @click="loadAccount()">
        {{ $t("user.common.refresh") }}
      </button>
    </p>
    <!-- 状态读取与目录并行，目录就绪前隐藏操作面板；组件继续独立处理会话和提交后复核。 -->
    <VipAutoPanel
      v-show="catalog"
      :levels="catalog?.levels || []"
      :currency="catalog?.summaryCurrency || 'USDT'"
      :current-level="session.member?.level"
      :summary="presentation?.autoSummary"
      @changed="loadAccount(true, $event)"
    />
    <PageState
      :loading="task.loading.value && !catalog"
      :error="visibleError"
      :empty="!catalog"
      @retry="load()"
    />
  </div>
</template>
<style scoped>
.ui-token-page { width: 100%; max-width: 1240px; margin: 0 auto; }
</style>
