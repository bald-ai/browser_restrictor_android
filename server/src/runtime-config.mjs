export function listenAddress(env = process.env) {
  const port = Number(env.VERIFY_PORT ?? env.PORT ?? "8787");
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("VERIFY_PORT or PORT must be an integer from 1 to 65535");
  }
  return {
    host: env.VERIFY_HOST ?? (env.PORT ? "0.0.0.0" : "127.0.0.1"),
    port,
  };
}
