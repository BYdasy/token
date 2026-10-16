<script setup lang="ts">
import { useToken } from '@/composables/useToken';
import { spinDuration } from '@/utils/activity-wheel';
const { unitName } = useToken();
defineProps<{ opened: boolean; amount?: string; busy?: boolean; durationSeconds?: number }>();
</script>
<template>
  <div class="treasure-scene" :class="{ opened, busy }" :style="{ '--treasure-duration': `${spinDuration(durationSeconds)}s` }" aria-live="polite">
    <div class="treasure-halo" />
    <div class="treasure-rays" />
    <div class="treasure-box" aria-hidden="true"><div class="treasure-lid"><i /></div><div class="treasure-base"><i /><b /></div></div>
    <div v-if="opened && amount" class="treasure-amount"><strong>{{ amount }}</strong><span>{{ unitName }}</span></div>
    <span v-for="index in 6" :key="index" class="treasure-spark" :style="{ '--spark-angle': `${index * 60}deg` }" />
  </div>
</template>
<style scoped>
.treasure-scene { position:relative; height:310px; width:min(100%,380px); margin:10px auto 0; perspective:700px; }
.treasure-halo { position:absolute; inset:45px 0 8px; border-radius:50%; background:radial-gradient(ellipse,#f8e1d5 0%,#fdf1e980 45%,transparent 70%); }
.treasure-box { position:absolute; left:calc(50% - 111px); bottom:35px; width:222px; height:150px; filter:drop-shadow(0 16px 15px rgb(120 67 82 / 15%)); }
.treasure-lid { position:absolute; left:0; top:0; height:69px; width:222px; border:6px solid #e5b396; border-radius:34px 34px 8px 8px; background:linear-gradient(120deg,#b77478,#784352); transform-origin:center bottom; transition:transform var(--treasure-duration) cubic-bezier(.18,.8,.18,1),opacity var(--treasure-duration); z-index:2; }
.treasure-lid:before,.treasure-base:before { content:''; position:absolute; left:32px; top:0; bottom:0; width:13px; background:linear-gradient(90deg,#d7977d,#f6d7bf,#d7977d); box-shadow:125px 0 #e5b396; }
.treasure-lid i { position:absolute; inset:12px 58px; border-top:1px solid #fdfaf866; border-radius:15px; }
.treasure-base { position:absolute; left:0; bottom:0; width:222px; height:90px; border:6px solid #e5b396; border-radius:4px 4px 18px 18px; background:linear-gradient(120deg,#a5656d,#613346); }
.treasure-base b { position:absolute; left:88px; top:-19px; width:34px; height:43px; border:4px solid #f4d5bd; border-radius:8px; background:#d7977d; z-index:3; }
.treasure-base b:after { content:''; position:absolute; width:7px; height:15px; border-radius:5px; background:#784352; top:10px; left:9px; }
.treasure-rays { position:absolute; width:220px; height:220px; left:calc(50% - 110px); top:20px; border-radius:50%; background:radial-gradient(circle,#f7d3b9,transparent 67%); opacity:0; transform:scale(.3); transition:opacity var(--treasure-duration),transform var(--treasure-duration); }
.treasure-amount { position:absolute; top:30px; left:10px; right:10px; display:grid; gap:5px; z-index:4; animation:treasure-reveal var(--treasure-duration) both; text-align:center; }
.treasure-amount strong { font-size:clamp(28px,7vw,42px); line-height:1.2; color:var(--vora-primary); overflow-wrap:anywhere; font-variant-numeric:tabular-nums; }
.treasure-amount span { color:var(--vora-muted); font-size:14px; }
.treasure-spark { position:absolute; left:50%; top:130px; height:6px; width:6px; background:#d69783; border-radius:2px; transform:rotate(var(--spark-angle)) translateY(-80px) scale(0); opacity:0; }
/* 连续开箱先即时复位，避免关箱过渡覆盖下一次开箱。 */
.treasure-scene:not(.opened) .treasure-lid,.treasure-scene:not(.opened) .treasure-rays { transition:none; }
.opened .treasure-lid { transform:translateY(-34px) rotateX(105deg); opacity:.75; }
.opened .treasure-rays { opacity:1; transform:scale(1.3); }
.opened .treasure-spark { animation:treasure-sparkle var(--treasure-duration) ease-out both; }
.busy:not(.opened) .treasure-box { animation:treasure-wait 1s ease-in-out infinite; }
@keyframes treasure-reveal { 0%,35% { opacity:0; transform:translateY(45px) scale(.6); } 100% { opacity:1; transform:translateY(0) scale(1); } }
@keyframes treasure-sparkle { 0%,30% { opacity:0; transform:rotate(var(--spark-angle)) translateY(-35px) scale(0); } 70% { opacity:1; } 100% { opacity:0; transform:rotate(var(--spark-angle)) translateY(-135px) scale(1.2); } }
@keyframes treasure-wait { 50% { transform:translateY(-5px); } }
@media(prefers-reduced-motion:reduce) { .treasure-lid,.treasure-rays { transition:none; } .treasure-amount { animation:none; } .treasure-spark,.treasure-box { animation:none!important; } }
</style>
