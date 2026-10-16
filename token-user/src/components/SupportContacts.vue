<script setup lang="ts">
import { useSupportContacts } from "@/composables/useSupportContacts";
const { contacts } = useSupportContacts();
const emit = defineEmits<{ selected: [] }>();
</script>
<template>
  <div v-if="contacts.length" class="support-contacts">
    <a
      v-for="(contact, index) in contacts"
      :key="contact.url + index"
      class="support-contact"
      :href="contact.url"
      target="_blank"
      rel="noopener noreferrer"
      @click="emit('selected')"
    >
      <img v-if="contact.image" :src="contact.image" alt="" />
      <van-icon v-else name="service-o" />
      <span>{{ contact.name }}</span
      ><van-icon name="arrow" />
    </a>
  </div>
  <van-empty v-else :description="$t('user.v11.features.unavailable')" />
</template>
<style scoped>
.support-contacts {
  display: grid;
  gap: 12px;
  text-align: start;
}
.support-contact {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  min-height: 68px;
  border-radius: 12px;
  background: var(--vora-bg, #faf6f2);
  color: var(--vora-primary, #773e51);
  font-weight: 600;
}
.support-contact img {
  width: 36px;
  height: 36px;
  object-fit: contain;
  flex-shrink: 0;
}
.support-contact > .van-icon:first-child {
  font-size: 30px;
}
.support-contact span {
  flex: 1;
  overflow-wrap: anywhere;
}
.support-contact:hover { background: #f2e0da; }
.support-contact:focus-visible { outline: 2px solid var(--vora-primary, #773e51); outline-offset: 3px; }
.support-contact > .van-icon:last-child { color: var(--vora-muted, #806e75); font-size: 14px; }
</style>
