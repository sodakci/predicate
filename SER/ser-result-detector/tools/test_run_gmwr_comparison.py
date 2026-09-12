#!/usr/bin/env python3
import unittest

from run_gmwr_comparison import parse_log, verdict_return_code


class ParseLogTest(unittest.TestCase):
    def test_timeout_takes_precedence_over_reject(self):
        text = "[[[[ REJECT ]]]]\n[[[[ TIMEOUT ]]]]\n"
        verdict, metrics, memory = parse_log(text)
        self.assertEqual(verdict, "TIMEOUT")
        self.assertEqual(verdict_return_code(verdict), 124)

    def test_accept(self):
        verdict, _, _ = parse_log("[[[[ ACCEPT ]]]]")
        self.assertEqual(verdict, "ACCEPT")
        self.assertEqual(verdict_return_code(verdict), 0)

    def test_reject(self):
        verdict, _, _ = parse_log("[[[[ REJECT ]]]]")
        self.assertEqual(verdict, "REJECT")
        self.assertEqual(verdict_return_code(verdict), -1)

    def test_error_without_marker(self):
        verdict, _, _ = parse_log("SAT solver crashed")
        self.assertEqual(verdict, "ERROR")
        self.assertIsNone(verdict_return_code(verdict))


if __name__ == "__main__":
    unittest.main()
