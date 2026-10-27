"""Fail-closed tests for P2 matrix status parsing; no device required."""
import json
import unittest

from p2_matrix import CASES, RUNNER, parse_matrix

RUNNER_CLASS = RUNNER.split("/", 1)[1]


def case(index, name, *, evidence=True, code=0, stack=None, extra_evidence=False):
    fields = (f"INSTRUMENTATION_STATUS: class={RUNNER_CLASS}\nINSTRUMENTATION_STATUS: current={index}\n"
              f"INSTRUMENTATION_STATUS: numtests={len(CASES)}\nINSTRUMENTATION_STATUS: test={name}\n")
    text = fields + "INSTRUMENTATION_STATUS_CODE: 1\n"
    for _ in range(2 if extra_evidence else (1 if evidence else 0)):
        text += f"INSTRUMENTATION_STATUS: matrix={json.dumps({'case': name, 'evidence': {'ok': True}})}\nINSTRUMENTATION_STATUS_CODE: 2\n"
    text += fields + (f"INSTRUMENTATION_STATUS: stack={stack}\n" if stack else "") + f"INSTRUMENTATION_STATUS_CODE: {code}\n"
    return text


def output(parts, final="-1"):
    return "".join(parts) + f"INSTRUMENTATION_RESULT: stream=done\nINSTRUMENTATION_CODE: {final}\n"


class MatrixParsingTest(unittest.TestCase):
    def all_cases(self, **overrides):
        return [case(i + 1, name, **overrides.get(name, {})) for i, name in enumerate(CASES)]

    def test_all_passed(self):
        results = parse_matrix(output(self.all_cases()).replace("\n", "\r\n"))
        self.assertTrue(all(item["status"] == "passed" for item in results.values()))

    def test_failure_keeps_multiline_stack(self):
        results = parse_matrix(output(self.all_cases(**{CASES[2]: {"code": -2, "evidence": False,
                                                                     "stack": "IllegalStateException: boom\n\tat x"}}), final="0"))
        self.assertEqual(results[CASES[2]]["status"], "failed")
        self.assertIn("\tat x", results[CASES[2]]["failure"])

    def test_pass_without_evidence_is_failed(self):
        results = parse_matrix(output(self.all_cases(**{CASES[0]: {"evidence": False}})))
        self.assertEqual(results[CASES[0]]["status"], "failed")

    def test_missing_case_rejected(self):
        with self.assertRaises(ValueError):
            parse_matrix(output(self.all_cases()[:-1]))

    def test_repeated_evidence_rejected(self):
        with self.assertRaises(ValueError):
            parse_matrix(output(self.all_cases(**{CASES[1]: {"extra_evidence": True}})))

    def test_truncated_run_rejected(self):
        with self.assertRaises(ValueError):
            parse_matrix("".join(self.all_cases()))

    def test_all_passing_with_failed_final_rejected(self):
        with self.assertRaises(ValueError):
            parse_matrix(output(self.all_cases(), final="0"))


if __name__ == "__main__":
    unittest.main()
