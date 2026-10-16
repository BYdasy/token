import { requestClient } from './request';

export interface CompanyFile {
  id: number;
  fileName: string;
  names: Record<string, string>;
  resourceType: 'PDF' | 'LINK';
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
  requestClient.get<{ globalScope: boolean; maxFileBytes: number }>(`${base}/options`, { signal });
export const fetchCompanyFiles = (params: CompanyFileQuery, signal?: AbortSignal) =>
  requestClient.get<{ records: CompanyFile[]; total: number }>(base, { params, signal });
export const uploadCompanyFile = (file: File, signal?: AbortSignal) =>
  requestClient.upload<CompanyFile>(base, { file }, { signal });
export const updateCompanyFile = (
  id: number,
  data: Pick<CompanyFile, 'fileName' | 'names' | 'externalUrl' | 'enabled' | 'sortNo' | 'version'>,
  signal?: AbortSignal,
) => requestClient.put<CompanyFile>(`${base}/${id}`, data, { signal });
export const createCompanyLink = (data: Pick<CompanyFile, 'fileName' | 'names' | 'externalUrl'>, signal?: AbortSignal) =>
  requestClient.post<CompanyFile>(`${base}/links`, data, { signal });
export const downloadCompanyFile = (id: number, signal?: AbortSignal) =>
  requestClient.download<Blob>(`${base}/${id}/pdf`, { signal });
