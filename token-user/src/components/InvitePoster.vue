<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import QRCode from "qrcode";
import { showSuccessToast, showFailToast } from "vant";
import { useTask } from "@/composables/useTask";
import { copyText } from "@/utils/format";
import { imageSource } from "@/utils/image-source";
import { brandAsset } from "@/utils/brand-assets";

const props = defineProps<{
  inviteCode: string;
  inviteUrl: string;
  siteName: string;
  siteLogo?: string;
}>();
const { t, locale } = useI18n();
const action = useTask();
const posterUrl = ref("");
const posterBlob = ref<Blob>();
const error = ref("");
let generation = 0;
let disposed = false;
const posterScene = `${import.meta.env.BASE_URL}images/vora/invitation.png`;
const posterMark = brandAsset('icon-512.png');

function loadPosterImage(source: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const picture = new Image();
    const timer = window.setTimeout(() => reject(new Error(t("user.ui2.team.posterFailed"))), 8000);
    picture.crossOrigin = "anonymous";
    picture.onload = () => { window.clearTimeout(timer); resolve(picture); };
    picture.onerror = () => { window.clearTimeout(timer); reject(new Error(t("user.ui2.team.posterFailed"))); };
    picture.src = source;
  });
}

// 在本地绘制海报和真实邀请二维码，不发送邀请码给第三方图片服务。
function wrapText(
  context: CanvasRenderingContext2D,
  value: string,
  x: number,
  y: number,
  width: number,
  lineHeight: number,
  maxLines = 3
) {
  let line = "";
  let count = 0;
  for (const character of Array.from(value)) {
    if (context.measureText(line + character).width > width && line) {
      context.fillText(line, x, y + count * lineHeight);
      count++;
      if (count >= maxLines) return;
      line = character;
    } else line += character;
  }
  context.fillText(line, x, y + count * lineHeight);
}
async function generate() {
  const version = ++generation;
  error.value = "";
  posterBlob.value = undefined;
  if (posterUrl.value) URL.revokeObjectURL(posterUrl.value);
  posterUrl.value = "";
  if (!props.inviteCode) return;
  try {
    const canvas = document.createElement("canvas");
    canvas.width = 640;
    canvas.height = 920;
    const context = canvas.getContext("2d");
    if (!context) throw new Error(t("user.ui2.team.posterFailed"));
    const [scene, mark] = await Promise.all([
      loadPosterImage(posterScene),
      loadPosterImage(imageSource(props.siteLogo) || posterMark).catch(() => loadPosterImage(posterMark)),
    ]);
    context.fillStyle = "#FDF1E9";
    context.fillRect(0, 0, 640, 920);
    const sceneHeight = scene.height * 640 / scene.width;
    context.drawImage(scene, 0, 420 - sceneHeight, 640, sceneHeight);
    const wash = context.createLinearGradient(0, 0, 0, 440);
    wash.addColorStop(0, "#fff9f1f5");
    wash.addColorStop(0.58, "#fff9f1cf");
    wash.addColorStop(1, "#fff9f100");
    context.fillStyle = wash;
    context.fillRect(0, 0, 640, 440);
    context.drawImage(mark, 44, 32, 58, 58);
    context.fillStyle = "#3B142B";
    context.font = "600 34px Georgia, serif";
    wrapText(context, props.siteName, 116, 74, 475, 38, 2);
    context.font = "700 44px system-ui, sans-serif";
    wrapText(context, t("user.vora.team.posterHeadline"), 48, 156, 530, 58, 3);
    context.font = "400 22px system-ui, sans-serif";
    context.fillStyle = "#3B142B";
    wrapText(context, t("user.vora.team.posterDescription"), 48, 320, 500, 32, 2);
    context.fillStyle = "#FDFAF8";
    context.beginPath();
    context.roundRect(40, 410, 560, 448, 28);
    context.fill();
    const qr = document.createElement("canvas");
    await QRCode.toCanvas(qr, props.inviteUrl, {
      width: 250,
      margin: 2,
      errorCorrectionLevel: "M",
    });
    context.drawImage(qr, 195, 437, 250, 250);
    context.fillStyle = "#806e75";
    context.textAlign = "center";
    context.font = "400 21px system-ui, sans-serif";
    context.fillText(t("user.team.inviteCode"), 320, 720, 500);
    context.fillStyle = "#3B142B";
    context.font = "700 34px system-ui, sans-serif";
    context.fillText(props.inviteCode, 320, 766, 500);
    context.fillStyle = "#806e75";
    context.font = "400 20px system-ui, sans-serif";
    context.fillText(t("user.ui.account.scanJoin"), 320, 815, 500);
    const blob = await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob(
        (value) =>
          value
            ? resolve(value)
            : reject(new Error(t("user.ui2.team.posterFailed"))),
        "image/png"
      )
    );
    if (disposed || version !== generation) return;
    posterBlob.value = blob;
    posterUrl.value = URL.createObjectURL(blob);
  } catch (cause) {
    if (!disposed && version === generation)
      error.value =
        cause instanceof Error
          ? cause.message
          : t("user.ui2.team.posterFailed");
  }
}
async function download() {
  if (!posterUrl.value) await generate();
  if (!posterUrl.value || disposed) { if (error.value) showFailToast(error.value); return; }
  const link = document.createElement("a");
  link.href = posterUrl.value;
  link.download = "invitation-poster.png";
  link.click();
}
async function sharePoster(instagram = false) {
  return action.run(async () => {
    if (!posterBlob.value) await generate();
    if (!posterBlob.value || disposed) return;
    const file = new File([posterBlob.value], "invitation-poster.png", {
      type: "image/png",
    });
    if (navigator.canShare?.({ files: [file] }) && navigator.share) {
      try {
        await navigator.share({
          files: [file],
          title: props.siteName,
          text: `${t("user.ui.account.shareText")} ${props.inviteUrl}`,
        });
      } catch (cause) {
        if (!(cause instanceof DOMException && cause.name === "AbortError"))
          throw cause;
      }
    } else {
      download();
      await copyText(props.inviteUrl);
      showSuccessToast(
        t(
          instagram
            ? "user.ui2.team.instagramHint"
            : "user.ui2.team.posterSaved"
        )
      );
    }
  });
}
defineExpose({ download, shareInstagram: () => sharePoster(true) });
watch(
  () => [props.inviteCode, props.inviteUrl, props.siteName, props.siteLogo, locale.value],
  generate,
  { immediate: true }
);
onBeforeUnmount(() => {
  disposed = true;
  generation++;
  if (posterUrl.value) URL.revokeObjectURL(posterUrl.value);
});
</script>

<template><span hidden aria-hidden="true" /></template>
