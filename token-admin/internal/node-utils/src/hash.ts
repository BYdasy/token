import { createHash } from 'node:crypto';

/**
 * 生产基于内容的 hash，可自定义长度
 * @param content 要计算哈希的内容
 * @param hashLSize 哈希长度
 */
function generatorContentHash(content: string, hashLSize?: number) {
  const hash = createHash('md5').update(content, 'utf8').digest('hex');

  if (hashLSize) {
    return hash.slice(0, hashLSize);
  }

  return hash;
}

export { generatorContentHash };
