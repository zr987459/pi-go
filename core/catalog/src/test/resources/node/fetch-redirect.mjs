// 测试用：把发往真实服务商的 https 请求改写到本机 mock 服务器（PIGO_MOCK_BASE=http://127.0.0.1:PORT）。
// 只在测试进程里通过 `node --import` 加载，生产脚本不含此文件。
const base = process.env.PIGO_MOCK_BASE;
const realFetch = globalThis.fetch;
globalThis.fetch = (input, init) => {
  const url = new URL(typeof input === "string" || input instanceof URL ? input : input.url);
  if (base && url.protocol === "https:") {
    const target = `${base}/${url.host}${url.pathname}${url.search}`;
    return realFetch(target, init);
  }
  return realFetch(input, init);
};
