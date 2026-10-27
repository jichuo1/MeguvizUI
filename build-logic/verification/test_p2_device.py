"""Fail-closed tests for raw Android instrumentation status parsing; no device required."""
import unittest

from p2_device import check_instrumentation


RUNNER = "io.github.jichuo1.meguviz.samples.plan.test/io.github.jichuo1.meguviz.samples.plan.PlanInstrumentation"
RUNNER_CLASS = RUNNER.split("/", 1)[1]


def event(name, current, total, code, *, class_name=RUNNER_CLASS, omitted=()):
    fields = {"class": class_name, "current": str(current), "numtests": str(total), "test": name}
    lines = [f"INSTRUMENTATION_STATUS: {key}={value}" for key, value in fields.items() if key not in omitted]
    lines.append(f"INSTRUMENTATION_STATUS_CODE: {code}")
    return "\n".join(lines) + "\n"


def case(name, current, total=2):
    return event(name, current, total, 1) + event(name, current, total, 0)


def output(*events, final="-1"):
    return "".join(events) + f"INSTRUMENTATION_RESULT: stream=done\nINSTRUMENTATION_CODE: {final}\n"


class InstrumentationParsingTest(unittest.TestCase):
    def assert_rejected(self, value, expected=2):
        with self.assertRaises((RuntimeError, ValueError)):
            check_instrumentation(value, RUNNER, expected)

    def test_success_with_actual_status_format_and_crlf(self):
        actual = output(case("direct_and_plan_same_native_geometry", 1),
                        case("failed_factory_releases_already_mounted_nodes", 2))
        self.assertIsNone(check_instrumentation(actual.replace("\n", "\r\n"), RUNNER, 2))

    def test_success_single_test(self):
        self.assertIsNone(check_instrumentation(output(case("generated_factory", 1, 1)), RUNNER, 1))

    def test_duplicate_name_rejected_even_with_distinct_indices(self):
        self.assert_rejected(output(case("same", 1), case("same", 2)))

    def test_missing_fields_on_one_or_both_events_are_rejected(self):
        for missing in ("class", "test", "current", "numtests"):
            with self.subTest(field=missing, position="start"):
                self.assert_rejected(output(event("first", 1, 2, 1, omitted=(missing,)),
                                            event("first", 1, 2, 0), case("second", 2)))
            with self.subTest(field=missing, position="both"):
                self.assert_rejected(output(event("first", 1, 2, 1, omitted=(missing,)),
                                            event("first", 1, 2, 0, omitted=(missing,)), case("second", 2)))

    def test_missing_start_or_end_event_rejected(self):
        self.assert_rejected(output(event("first", 1, 2, 1), case("second", 2)))
        self.assert_rejected(output(event("first", 1, 2, 0), case("second", 2)))

    def test_extra_event_or_extra_complete_test_rejected(self):
        self.assert_rejected(output(case("first", 1), case("second", 2), event("extra", 3, 2, 1)))
        self.assert_rejected(output(case("first", 1), case("second", 2), case("extra", 3)))

    def test_failure_and_unexpected_status_never_pass(self):
        for code in (-2, -1, 2):
            with self.subTest(code=code):
                self.assert_rejected(output(event("first", 1, 2, 1), event("first", 1, 2, code), case("second", 2)))

    def test_wrong_missing_and_duplicate_final_result_rejected(self):
        body = case("first", 1) + case("second", 2)
        for final in ("0", "1", "-2"):
            with self.subTest(final=final):
                self.assert_rejected(output(body, final=final))
        self.assert_rejected(body)
        self.assert_rejected(output(body) + "INSTRUMENTATION_CODE: -1\n")

    def test_mismatched_runner_test_or_index_rejected(self):
        self.assert_rejected(output(event("first", 1, 2, 1, class_name="other.Runner"),
                                    event("first", 1, 2, 0, class_name="other.Runner"), case("second", 2)))
        self.assert_rejected(output(event("first", 1, 2, 1), event("different", 1, 2, 0), case("second", 2)))
        self.assert_rejected(output(case("first", 2), case("second", 1)))
        self.assert_rejected(output(case("first", 1, 3), case("second", 2, 3)))

    def test_empty_and_malformed_status_rejected(self):
        self.assert_rejected("")
        self.assert_rejected(output(case("first", 1), event("second", 2, 2, "not-a-number"),
                                    event("second", 2, 2, 0)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
