<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useSession } from "@/stores/session";
import { useSupportContacts } from "@/composables/useSupportContacts";
import { useResponsivePopup } from "@/composables/useResponsivePopup";
import { useEdgeFloating } from "@/composables/useEdgeFloating";
import SupportContacts from "./SupportContacts.vue";
const session = useSession(),
  route = useRoute();
const { contacts } = useSupportContacts();
const { popupPosition } = useResponsivePopup();
const floating = useEdgeFloating(() => session.config?.features?.supportFloatingBottom ?? 84);
const floatingElement = floating.element;
const open = ref(false),
  imageFailed = ref(false);
const visible = computed(
  () =>
    session.authenticated &&
    contacts.value.length > 0 &&
    (session.config?.features?.supportFloatingScope === "ALL" ||
      route.path === "/")
);
watch(
  () => session.config?.features?.supportFloatingImage,
  () => {
    imageFailed.value = false;
  }
);
watch(
  () => route.path,
  () => {
    open.value = false;
  }
);
watch(visible, (value) => {
  if (!value) open.value = false;
});
</script>
<template>
  <template v-if="visible">
    <button
      ref="floatingElement"
      class="support-floating"
      :class="{ 'is-dragging': floating.dragging.value }"
      :style="floating.style.value"
      type="button"
      :aria-label="$t('user.v11.features.support')"
      @pointerdown="floating.pointerdown"
      @lostpointercapture="floating.pointerend"
      @click.capture="floating.clickCapture"
      @click="open = true"
    >
      <img
        v-if="session.config?.features?.supportFloatingImage && !imageFailed"
        :src="session.config.features.supportFloatingImage"
        :draggable="false"
        alt=""
        @error="imageFailed = true"
      />
      <van-icon v-else name="service-o" />
    </button>
    <van-popup
      v-model:show="open"
      round
      closeable
      :position="popupPosition"
      class="ui-responsive-popup support-popup"
    >
      <h2>{{ $t("user.v11.features.support") }}</h2>
      <p>{{ $t("user.v11.features.supportIntro") }}</p>
      <SupportContacts @selected="open = false" />
    </van-popup>
  </template>
</template>
<style scoped>
.support-floating {
  position: fixed;
  right: max(24px, env(safe-area-inset-right));
  bottom: 30px;
  z-index: 85;
  display: grid;
  place-items: center;
  width: 62px;
  height: 62px;
  border: 0;
  border-radius: 50%;
  background: var(--vora-primary, #773e51);
  color: #fffdfb;
  box-shadow: 0 6px 22px #773e512e;
  cursor: pointer;
  font-size: 32px;
  padding: 0;
  touch-action: none;
  user-select: none;
  transition: left .18s ease, bottom .18s ease;
}
.support-floating.is-dragging { transition: none; cursor: grabbing; }
.support-floating img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  border-radius: inherit;
}
.support-popup {
  background: var(--vora-surface, #fffdfb);
  color: var(--vora-ink, #392d32);
  padding: 26px 22px;
  width: min(480px, 94vw);
  max-height: 82dvh;
  overflow: auto;
}
.support-popup h2 {
  margin: 0 30px 12px 0;
}
.support-popup p {
  line-height: 1.6;
  color: var(--vora-muted, #806e75);
  margin-bottom: 22px;
}
.support-floating:focus-visible { outline: 3px solid var(--vora-primary, #773e51); outline-offset: 4px; }
@media (prefers-reduced-motion: reduce) { .support-floating { transition: none; } }
@media (max-width: 800px) {
  .support-floating {
    right: 18px;
    width: 56px;
    height: 56px;
  }
}
@media (max-width: 700px) {
  .support-popup {
    width: 100%;
    padding-bottom: calc(26px + env(safe-area-inset-bottom));
  }
}
</style>
