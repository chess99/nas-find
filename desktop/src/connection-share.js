import qrcode from './vendor/qrcode-generator.js';

export const CONNECTION_PREFIX = 'nasfind://connection/v1?data=';

export function encodeConnection(config) {
  if (typeof config?.server !== 'string' || typeof config?.password !== 'string' || !config.password) {
    throw new Error('没有可分享的已保存连接');
  }
  const data = new TextEncoder().encode(JSON.stringify({server: config.server, password: config.password}));
  // Bound QR density and keep both sides on the same small, versioned format.
  if (data.length > 1500) throw new Error('连接配置过长，无法生成可可靠识别的二维码');
  const base64 = btoa(String.fromCharCode(...data)).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
  return CONNECTION_PREFIX + base64;
}

export function connectionMatrix(config) {
  const code = qrcode(0, 'M');
  code.addData(encodeConnection(config), 'Byte');
  code.make();
  return code;
}

export function drawConnection(canvas, config) {
  const code = connectionMatrix(config), size = code.getModuleCount(), quiet = 4, scale = 8;
  canvas.width = canvas.height = (size + quiet * 2) * scale;
  const context = canvas.getContext('2d');
  context.fillStyle = '#fff'; context.fillRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = '#172a21';
  for (let row = 0; row < size; row++) for (let col = 0; col < size; col++) {
    if (code.isDark(row, col)) context.fillRect((col + quiet) * scale, (row + quiet) * scale, scale, scale);
  }
}

export function canShare({connected, remembered, savedServer, server, password}) {
  return connected && remembered && !!savedServer && server.trim().replace(/\/+$/, '') === savedServer && !password;
}
