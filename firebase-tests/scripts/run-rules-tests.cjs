const { EventEmitter } = require("node:events");
const http = require("node:http");
const path = require("node:path");
const Mocha = require("mocha");
const { waitForExactSignal } = require("./exact-signal.cjs");

const HUB_SAFETY_MS = 30_000;
const SUITE_SAFETY_MS = 300_000;
const EXPECTED_RULE_TESTS = 41;
const requiredEmulators = ["firestore", "storage"];

function awaitEmulatorReadiness() {
  const hub = process.env.FIREBASE_EMULATOR_HUB;
  if (!hub) throw new Error("FIREBASE_EMULATOR_HUB is missing; run this test through firebase emulators:exec");

  const events = new EventEmitter();
  let request;
  console.log(`RULES_HARNESS event=emulators-subscribed hub=${hub}`);
  return waitForExactSignal({
    emitter: events,
    signal: "ready",
    safetyMs: HUB_SAFETY_MS,
    errorSignal: "failure",
    trigger: () => {
      request = http.get(`http://${hub}/emulators`, (response) => {
        let body = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => { body += chunk; });
        response.on("end", () => {
          try {
            if (response.statusCode !== 200) throw new Error(`Firebase Emulator Hub returned HTTP ${response.statusCode}`);
            const emulators = JSON.parse(body);
            const missing = requiredEmulators.filter((name) => !emulators[name]);
            if (missing.length > 0) throw new Error(`Firebase Emulator Hub is missing: ${missing.join(", ")}`);
            events.emit("ready", emulators);
          } catch (error) {
            events.emit("failure", error);
          }
        });
      });
      request.on("error", (error) => events.emit("failure", error));
      return () => request.destroy();
    },
  });
}

function runRulesSuite() {
  const events = new EventEmitter();
  const mocha = new Mocha({ reporter: "spec", timeout: 0 });
  mocha.addFile(path.resolve(__dirname, "../test/security-rules.test.cjs"));
  mocha.addFile(path.resolve(__dirname, "../test/catalog-projection-rules.test.cjs"));
  mocha.addFile(path.resolve(__dirname, "../test/server-contract.test.cjs"));

  let runner;
  const passedTestNames = [];
  console.log("RULES_HARNESS event=suite-subscribed");
  return waitForExactSignal({
    emitter: events,
    signal: "complete",
    safetyMs: SUITE_SAFETY_MS,
    errorSignal: "failure",
    trigger: () => {
      console.log("RULES_HARNESS event=suite-started");
      runner = mocha.run((failures) => events.emit("complete", {
        failures,
        tests: runner.stats.tests,
        passes: runner.stats.passes,
        pending: runner.stats.pending,
        testNames: passedTestNames,
      }));
      runner.on("pass", (completedTest) => passedTestNames.push(completedTest.fullTitle()));
      runner.on("error", (error) => events.emit("failure", error));
      return () => runner.abort();
    },
  });
}

async function main() {
  const emulators = await awaitEmulatorReadiness();
  const ready = requiredEmulators.map((name) => `${name}:${emulators[name].port}`).join(",");
  console.log(`RULES_HARNESS event=emulators-ready services=${ready}`);
  const result = await runRulesSuite();
  const countMatches = result.tests === EXPECTED_RULE_TESTS;
  console.log(
    `RULES_RESULT tests=${result.tests} passes=${result.passes} failures=${result.failures} pending=${result.pending} expected=${EXPECTED_RULE_TESTS}`,
  );
  console.log(`RULES_RESULT_JSON ${JSON.stringify(result)}`);
  console.log(
    `RULES_HARNESS event=teardown-complete failures=${result.failures} countMatches=${countMatches}`,
  );
  process.exitCode = result.failures === 0 && result.pending === 0 && countMatches ? 0 : 1;
}

main().catch((error) => {
  console.error(`RULES_HARNESS event=failure name=${error.name} message=${error.message}`);
  process.exitCode = 1;
});
