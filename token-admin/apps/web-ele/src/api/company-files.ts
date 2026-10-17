import { requestClient } from './request';

export interface CompanyFile {
  id: number;
  fileName: string;
  names: Record<string, string>;
  resourceType: 'PDF' | 'LINK' | 'VIDEO' | 'VIDEO_LINK';
  homepage: boolean;
  mimeType?: string;
  playbackType: 'NONE' | 'FILE' | 'DIRECT' | 'YOUTUBE' | 'VIMEO';
  embedUrl?: string;
  externalUrl?: string;
  size: number;
  url: string;
  enabled: boolean;
  sortNo: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}
export interface CompanyFileQuery {
  page: number;
  size: number;
  keyword: string;
  enabled: '' | '0' | '1';
}
const base = '/compute/admin/company-files';
export const fetchCompanyFileOptions = (signal?: AbortSignal) =>
  requestClient.get<{ globalScope: boolean; maxFileBytes: number; maxVideoBytes: number }>(`${base}/options`, { signal });
export const fetchCompanyFiles = (params: CompanyFileQuery, signal?: AbortSignal) =>
  requestClient.get<{ records: CompanyFile[]; total: number }>(base, { params, signal });
export const uploadCompanyFile = (file: File, signal?: AbortSignal, onProgress?: (percent: number) => void) =>
  requestClient.upload<CompanyFile>(base, { file }, { signal, timeout: 300_000,
    onUploadProgress: (event) => { if (event.total) onProgress?.(Math.min(99, Math.round(event.loaded * 100 / event.total))); },
  });
export const updateCompanyFile = (
  id: number,
  data: Pick<CompanyFile, 'fileName' | 'names' | 'externalUrl' | 'enabled' | 'homepage' | 'sortNo' | 'version'>,
  signal?: AbortSignal,
) => requestClient.put<CompanyFile>(`${base}/${id}`, data, { signal });
export const createCompanyLink = (data: Pick<CompanyFile, 'fileName' | 'names' | 'externalUrl'>, signal?: AbortSignal) =>
  requestClient.post<CompanyFile>(`${base}/links`, data, { signal });
export const createCompanyVideoLink = (data: Pick<CompanyFile, 'fileName' | 'names' | 'externalUrl' | 'homepage'>, signal?: AbortSignal) =>
  requestClient.post<CompanyFile>(`${base}/video-links`, data, { signal });
export const downloadCompanyFile = (id: number, signal?: AbortSignal, video = false) =>
  requestClient.download<Blob>(`${base}/${id}/${video ? 'video' : 'pdf'}`, { signal, timeout: 300_000 });
