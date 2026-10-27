"""Fail-closed tests for P2 performance parsing, statistics and admission; no device required."""
import json
import tomllib
import unittest

from p2_performance import CRITERIA, PROFILES, RUNNER, evaluate, parse_run, thermal_status

CRITERIA_VALUE = tomllib.loads(CRITERIA.read_text("utf-8"))
RUNNER_CLASS = RUNNER.split("/", 1)[1]
N = CRITERIA_VALUE["minimum_samples"]


def record(profile, a=None, b=None, warmup=20, **overrides):
    value = {"schema_version": 1, "profile": profile, "scenario": CRITERIA_VALUE["profiles"][profile]["scenario"],
             "metric": "m", "unit": "ns", "warmup": warmup, "order": "ABBA-interleaved",
             "A": {"samples": a if a is not None else [1000 + i for i in range(N)], "aux": {}},
             "B": {"samples": b if b is not None else [1000 + i for i in range(N)], "aux": {}}}
    value.update(overrides)
    return value


def experiment(index, value, *, end_code=0, stack=None, name=None):
    profile = value.get("profile", "plan")
    test = name or f"{profile}_{CRITERIA_VALUE['profiles'].get(profile, {'scenario': 'x'})['scenario']}"
    fields = f"INSTRUMENTATION_STATUS: class={RUNNER_CLASS}\nINSTRUMENTATION_STATUS: current={index}\n" \
             f"INSTRUMENTATION_STATUS: numtests={len(PROFILES)}\nINSTRUMENTATION_STATUS: test={test}\n"
    end = fields + (f"INSTRUMENTATION_STATUS: stack={stack}\n" if stack else "")
    return (fields + "INSTRUMENTATION_STATUS_CODE: 1\n"
            + f"INSTRUMENTATION_STATUS: p2perf={json.dumps(value)}\nINSTRUMENTATION_STATUS_CODE: 2\n"
            + end + f"INSTRUMENTATION_STATUS_CODE: {end_code}\n")


def output(values, final="-1", **kwargs):
    body = "".join(experiment(i + 1, v, **kwargs) for i, v in enumerate(values))
    return body + f"INSTRUMENTATION_RESULT: stream=done\nINSTRUMENTATION_CODE: {final}\n"


class ParsingTest(unittest.TestCase):
    def rejected(self, text, samples=N, warmup=20):
        with self.assertRaises(ValueError):
            parse_run(text, CRITERIA_VALUE, samples, warmup)

    def test_complete_run_with_crlf(self):
        text = output([record(p) for p in PROFILES]).replace("\n", "\r\n")
        self.assertEqual(set(parse_run(text, CRITERIA_VALUE, N, 20)), set(PROFILES))

    def test_missing_experiment(self):
        self.rejected(output([record(p) for p in PROFILES[:-1]]))

    def test_duplicate_profile(self):
        self.rejected(output([record(p) for p in PROFILES[:-1]] + [record("plan")]))

    def test_unsuccessful_final_code(self):
        self.rejected(output([record(p) for p in PROFILES], final="0"))

    def test_failed_experiment_with_multiline_stack(self):
        self.rejected(output([record(p) for p in PROFILES], end_code=-2, stack="boom\n\tat x"))

    def test_empty_and_short_samples(self):
        self.rejected(output([record(PROFILES[0], a=[])] + [record(p) for p in PROFILES[1:]]))
        self.rejected(output([record(PROFILES[0], b=[5] * (N - 1))] + [record(p) for p in PROFILES[1:]]))

    def test_non_positive_or_boolean_samples(self):
        self.rejected(output([record(PROFILES[0], a=[0] * N)] + [record(p) for p in PROFILES[1:]]))
        self.rejected(output([record(PROFILES[0], b=[True] * N)] + [record(p) for p in PROFILES[1:]]))

    def test_scenario_warmup_or_unit_mismatch(self):
        self.rejected(output([record(PROFILES[0], scenario="other")] + [record(p) for p in PROFILES[1:]]))
        self.rejected(output([record(p) for p in PROFILES]), warmup=5)
        self.rejected(output([record(PROFILES[0], unit="ms")] + [record(p) for p in PROFILES[1:]]))
        self.rejected(output([record(PROFILES[0], order="AB")] + [record(p) for p in PROFILES[1:]]))

    def test_malformed_measurement(self):
        text = output([record(p) for p in PROFILES]).replace("p2perf={", "p2perf={{", 1)
        self.rejected(text)


class AdmissionTest(unittest.TestCase):
    def test_bounded_overhead_admits_equal_cost(self):
        result = evaluate("codegen", record("codegen"), CRITERIA_VALUE)
        self.assertTrue(result["admitted"])
        self.assertAlmostEqual(result["median_ratio"], 1.0)

    def test_bounded_overhead_rejects_regression(self):
        slow = [1300 + i for i in range(N)]
        self.assertFalse(evaluate("layouts", record("layouts", b=slow), CRITERIA_VALUE)["admitted"])

    def test_benefit_requires_clear_reduction(self):
        same = evaluate("text", record("text"), CRITERIA_VALUE)
        self.assertFalse(same["admitted"])
        faster = evaluate("text", record("text", b=[500 + i for i in range(N)]), CRITERIA_VALUE)
        self.assertTrue(faster["admitted"] and faster["ratio_ci95"][1] < 0.9)

    def test_benefit_threshold_is_strict(self):
        half = evaluate("prewarm", record("prewarm", a=[2000] * N, b=[1000] * N), CRITERIA_VALUE)
        self.assertEqual(half["ratio_ci95"], [0.5, 0.5])
        self.assertFalse(half["admitted"])

    def test_too_few_samples(self):
        with self.assertRaises(ValueError):
            evaluate("plan", record("plan", a=[1] * 10, b=[1] * 10), CRITERIA_VALUE)

    def test_bootstrap_is_deterministic(self):
        noisy = [1000 + (i * 37) % 200 for i in range(N)]
        first = evaluate("plan", record("plan", b=noisy), CRITERIA_VALUE)
        self.assertEqual(first["ratio_ci95"], evaluate("plan", record("plan", b=noisy), CRITERIA_VALUE)["ratio_ci95"])

    def test_statistics_follow_criteria(self):
        for profile in ("plan", "codegen", "layouts"):
            self.assertEqual(evaluate(profile, record(profile), CRITERIA_VALUE)["statistic"], "paired-ratio")
        for profile in ("recycler", "text", "prewarm"):
            self.assertEqual(evaluate(profile, record(profile), CRITERIA_VALUE)["statistic"], "median-ratio")

    def test_paired_ratio_cancels_shared_drift(self):
        # Bimodal device state shared by both groups of an iteration; B is 2% slower in every pair.
        drift = [1000 if i % 3 else 3000 for i in range(N)]
        a = [value + i % 7 for i, value in enumerate(drift)]
        b = [round(value * 1.02) for value in a]
        result = evaluate("plan", record("plan", a=a, b=b), CRITERIA_VALUE)
        self.assertTrue(result["admitted"])
        self.assertAlmostEqual(result["median_ratio"], 1.02, places=2)
        self.assertLess(result["ratio_ci95"][1], 1.03)

    def test_paired_ratio_rejects_consistent_regression(self):
        drift = [1000 if i % 3 else 3000 for i in range(N)]
        result = evaluate("layouts", record("layouts", a=drift, b=[v * 1.2 for v in drift]), CRITERIA_VALUE)
        self.assertFalse(result["admitted"])

    def test_paired_ratio_rejects_half_the_pairs_regressing(self):
        # Unpaired medians look equal (ratio 0.97), but half the pairs show B 30% slower.
        a = [1000 + (i % 2) * 1000 for i in range(N)]
        b = [1300 if i % 2 == 0 else 1600 for i in range(N)]
        result = evaluate("codegen", record("codegen", a=a, b=b), CRITERIA_VALUE)
        self.assertLess(result["unpaired_median_ratio"], 1.0)
        self.assertFalse(result["admitted"])

    def test_unknown_statistic(self):
        criteria = {**CRITERIA_VALUE, "profiles": {**CRITERIA_VALUE["profiles"],
                    "plan": {**CRITERIA_VALUE["profiles"]["plan"], "statistic": "mean"}}}
        with self.assertRaises(ValueError):
            evaluate("plan", record("plan"), criteria)

    def test_thermal_status(self):
        self.assertEqual(thermal_status("IsStatusOverride: false\nThermal Status: 0\n"), 0)
        with self.assertRaises(ValueError):
            thermal_status("no status")


if __name__ == "__main__":
    unittest.main()
