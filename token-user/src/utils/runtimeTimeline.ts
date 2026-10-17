import type { RuntimeSample, UiPresentationConfig } from '@/api/presentation';

export type PlatformRuntime = UiPresentationConfig['runtime'];

/** 使用服务器时间加本地单调计时，设备时钟偏差不会快进或倒放展示快照。 */
export function runtimeSampleAt(runtime: PlatformRuntime, elapsedMs: number): RuntimeSample | undefined {
  if (!runtime.enabled || runtime.mode !== 'AUTO') return;
  const now = Date.parse(runtime.serverTime || '') + Math.max(0, elapsedMs);
  if (!Number.isFinite(now)) return;
  let sample: RuntimeSample | undefined;
  for (const candidate of runtime.samples || []) {
    const at = Date.parse(candidate.at);
    if (Number.isFinite(at) && at <= now && (!sample || at > Date.parse(sample.at))) sample = candidate;
  }
  return sample;
}

export function runtimeTimelineAvailable(runtime: PlatformRuntime, elapsedMs: number): boolean {
  if (!runtime.enabled || runtime.mode !== 'AUTO' || !runtime.samples?.length) return false;
  const end = Math.max(...runtime.samples.map(sample => Date.parse(sample.at)));
  const now = Date.parse(runtime.serverTime || '') + Math.max(0, elapsedMs);
  return Number.isFinite(now) && now < end + runtimeSampleMilliseconds(runtime);
}

export function runtimeSampleMilliseconds(runtime: PlatformRuntime): number {
  const seconds = Number(runtime.sampleSeconds ?? runtime.changeSeconds ?? 2);
  return Math.min(300, Math.max(1, Number.isFinite(seconds) ? seconds : 2)) * 1000;
}

export function runtimeSyncMilliseconds(runtime?: PlatformRuntime): number {
  const seconds = Number(runtime?.refreshSeconds);
  return Math.min(300, Math.max(10, Number.isFinite(seconds) ? seconds : 60)) * 1000;
}
