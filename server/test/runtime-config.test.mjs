import assert from "node:assert/strict";
import test from "node:test";
import { listenAddress } from "../src/runtime-config.mjs";

test("Railway's assigned port accepts traffic from its proxy", () => {
  assert.deepEqual(listenAddress({ PORT: "43210" }), { host: "0.0.0.0", port: 43210 });
});

test("existing VPS deployments keep their loopback default and overrides", () => {
  assert.deepEqual(listenAddress({}), { host: "127.0.0.1", port: 8787 });
  assert.deepEqual(listenAddress({ VERIFY_HOST: "127.0.0.2", VERIFY_PORT: "8788", PORT: "8080" }), {
    host: "127.0.0.2", port: 8788,
  });
});

test("invalid ports fail startup instead of silently listening somewhere else", () => {
  for (const port of ["", "no-port", "0", "-1", "1.5", "65536"]) {
    assert.throws(() => listenAddress({ PORT: port }), /must be an integer/);
  }
});
