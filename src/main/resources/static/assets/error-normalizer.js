(function (global) {
  const htmlPattern = /<!doctype|<html|<head|<body|<script|<style/i;
  const stackPattern = /(?:\n|\r|\s)at\s+[\w.$]+\([^)]*\)|(?:java|org|com)\.[\w.$]+(?:Exception|Error)/g;
  const unixPath = /(?:\/Users\/|\/home\/|\/var\/|\/private\/|\/opt\/)[^\s"'<>]+/g;
  const windowsPath = /[A-Za-z]:\\(?:[^\\\s"'<>]+\\)*[^\\\s"'<>]*/g;
  const secretPattern = /(authorization|api[-_ ]?key|access[-_ ]?token|webhook[-_ ]?secret)\s*[:=]\s*\S+/gi;

  function plainText(value) {
    const text = String(value == null ? "" : value);
    if (htmlPattern.test(text)) return "";
    return text
      .replace(stackPattern, "")
      .replace(unixPath, "[内部路径]")
      .replace(windowsPath, "[内部路径]")
      .replace(secretPattern, "$1=[已隐藏]")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, 420);
  }

  function normalize(error, fallback) {
    const defaultMessage = fallback || "请求未完成，请检查服务状态后重试";
    if (!error) {
      return {code: "REQUEST_FAILED", message: defaultMessage, action: "稍后重试", correlationId: null};
    }
    const source = error.body || error.responseBody || error;
    const rawMessage = source.detail || source.message || error.message || source.error;
    const message = plainText(rawMessage) || defaultMessage;
    return {
      code: plainText(source.code || error.code) || "REQUEST_FAILED",
      message,
      action: plainText(source.action) || "检查连接设置或稍后重试",
      correlationId: plainText(source.correlationId || error.correlationId) || null,
      status: error.status || source.status || null
    };
  }

  global.NexusErrors = {normalize, plainText};
})(window);
