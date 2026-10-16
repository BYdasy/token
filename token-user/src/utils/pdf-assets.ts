/** 与 Vite 从同一 PDF.js 依赖发行包输出的版本目录一致，所有解码资源走本站。 */
export function pdfAssetUrls() {
  const base = import.meta.env.BASE_URL || '/';
  const version = import.meta.env.VITE_PDFJS_ASSET_VERSION;
  const prefix = `${base.endsWith('/') ? base : `${base}/`}pdfjs/${version}/`;
  return {
    cMapUrl: `${prefix}cmaps/`,
    standardFontDataUrl: `${prefix}standard_fonts/`,
    wasmUrl: `${prefix}wasm/`,
    iccUrl: `${prefix}iccs/`,
  };
}

export function pdfWorkerUrl() {
  return `${pdfAssetUrls().cMapUrl.replace(/cmaps\/$/, '')}pdf.worker.js`;
}
