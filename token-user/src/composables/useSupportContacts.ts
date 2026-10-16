import { computed } from "vue";
import { useSession } from "@/stores/session";

/** 客服页与悬浮入口使用同一份公开配置，不额外读取业务接口。 */
export function useSupportContacts() {
  const session = useSession();
  const contacts = computed(() =>
    !session.config?.features?.supportEnabled
      ? []
      : (session.config.features.supportContacts || []).filter((contact) => {
          try {
            const url = new URL(contact.url);
            return (
              ["https:", "http:"].includes(url.protocol) &&
              !url.username &&
              !url.password
            );
          } catch {
            return false;
          }
        })
  );
  return { contacts };
}
