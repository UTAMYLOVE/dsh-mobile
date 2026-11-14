import { describe, expect, it } from "vitest";
import { classify, type GuardEnv } from "../src/classify.js";

const env: GuardEnv = {
  level: "smart",
  workspace: "/data/user/0/app.dshmobile/files/userdata/workspace",
  temp: ["/data/user/0/app.dshmobile/cache/tmp", "/tmp"],
  protectedRoots: ["/data/user/0/app.dshmobile/files/userdata/dsh-home"],
  home: "/data/user/0/app.dshmobile/files/userdata/home",
};
const run = (command: string) => classify("bash", { command }, env).kind;

describe("the app's own skill scripts", () => {
  it("run without a question when called through $DSH_HOME, as the bookkeeping skill does", () => {
    expect(run(`node "$DSH_HOME/skills/dshm-bookkeeping/life.mjs" add '[{"類型":"支出","金額":120}]'`)).toBe("allow");
    expect(run(`node "$DSH_HOME/skills/dshm-bookkeeping/life.mjs" today --json`)).toBe("allow");
  });
});
