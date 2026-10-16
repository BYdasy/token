// 入口基于 vue3-vant-mobile，保留 Vue、Router、Pinia、i18n 和 Vant 桌面触摸适配。
import { createApp } from "vue";
import Vant, { setDialogDefaultOptions } from "vant";
import "@vant/touch-emulator";
import "vant/lib/index.css";
import App from "./App.vue";
import router from "./router";
import pinia from "./stores";
import { i18n } from "./locales";
import "./styles/app.css";
import "./styles/activity.css";
import "./styles/ui.css";
import "./styles/ui-account.css";
import "./styles/ui-products.css";
import "./styles/ui-layout.css";
import "./styles/ui-selectors.css";
import "./styles/ui-inputs.css";
import { initializePwa } from "./utils/pwa";

setDialogDefaultOptions({ className: "ui-dialog", theme: "round-button", closeOnClickOverlay: false });
initializePwa();

createApp(App).use(pinia).use(router).use(i18n).use(Vant).mount("#app");
