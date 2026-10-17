import { ApiError, request } from './index';
import { i18n } from '@/locales';

export interface CompanyFile {
  id: number;
  fileName: string;
  resourceType: 'PDF' | 'LINK' | 'VIDEO' | 'VIDEO_LINK';
  playbackType?: 'FILE' | 'DIRECT' | 'YOUTUBE' | 'VIMEO' | 'NONE';
  embedUrl?: string;
  mimeType?: string;
  size: number;
  url: string;
}

export const fetchCompanyFiles = (signal?: AbortSignal) =>
  request<CompanyFile[]>('/user/company-files', 'GET', undefined, signal);

export const fetchHomeVideos = (signal?: AbortSignal) =>
  request<CompanyFile[]>('/public/company-videos', 'GET', undefined, signal, true);

export const companyVideoUrl = (id: number, publicVideo = false) => {
  if (!Number.isSafeInteger(id) || id <= 0) return '';
  return `${import.meta.env.VITE_API_BASE || '/api/compute'}/${publicVideo ? 'public/company-videos' : 'user/company-files'}/${id}/video`;
};

export const fetchCompanyVideo = (id: number, signal?: AbortSignal) => {
  if (!Number.isSafeInteger(id) || id <= 0) throw new ApiError(i18n.global.t('user.v20Company.notFound'));
  return request<Blob>(`/user/company-files/${id}/video`, 'GET', undefined, signal, true, 'video');
};

export async function fetchCompanyPdf(id: number, signal?: AbortSignal) {
  if (!Number.isSafeInteger(id) || id <= 0) throw new ApiError(i18n.global.t('user.v20Company.notFound'));
  // 只根据记录编号访问固定受保护接口，不使用接口返回的任意远程地址。
  const bytes = await request<ArrayBuffer>(`/user/company-files/${id}/pdf`, 'GET', undefined, signal, false, 'pdf');
  if (!bytes || bytes.byteLength > 20 * 1024 * 1024 || bytes.byteLength < 5 ||
      new TextDecoder().decode(bytes.slice(0, 5)) !== '%PDF-') {
    throw new ApiError(i18n.global.t('user.v20Company.previewFailed'));
  }
  return bytes;
}
