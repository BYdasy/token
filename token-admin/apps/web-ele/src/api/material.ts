import { requestClient } from './request';

export interface MaterialAsset {
  category?: string;
  createdAt?: string;
  fileName: string;
  fileSize: number;
  fileUrl: string;
  height?: number;
  id: number;
  mediaType: 'IMAGE' | 'VIDEO';
  mimeType: string;
  name: string;
  remark?: string;
  tags?: string;
  width?: number;
}

export async function fetchMaterials(data: Record<string, any>) {
  const result = await requestClient.post<any>('/V2/materials/page', data);
  const records = result?.records ?? result?.items ?? [];
  return {
    current: Number(result?.current ?? 1),
    items: records as MaterialAsset[],
    size: Number(result?.size ?? 24),
    total: Number(result?.total ?? records.length),
  };
}

export function fetchMaterialCategories() {
  return requestClient.get<string[]>('/V2/materials/categories');
}

export function uploadMaterial(file: File, data: Record<string, any> = {}) {
  return requestClient.upload<MaterialAsset>('/V2/materials/upload', {
    ...data,
    file,
  });
}

export function updateMaterial(id: number, data: Record<string, any>) {
  return requestClient.post<MaterialAsset>(`/V2/materials/${id}/update`, data);
}

export function deleteMaterial(id: number) {
  return requestClient.delete(`/V2/materials/${id}`);
}
