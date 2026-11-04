#!/usr/bin/env python3
"""Negative lifecycle controls must fail before a container mutation."""
import unittest
from unittest.mock import patch
import managed_runtime as runtime
class OwnedLifecycleTest(unittest.TestCase):
    def test_mismatched_database_never_starts_or_stops_a_container(self):
        saved={'id':'own-runtime','network':'own-network','database':'foreign-container','started':True,'containers':[]}
        with patch.object(runtime,'prepare',return_value=saved),patch.object(runtime,'load',return_value=saved),patch.object(runtime,'state',return_value={'container':'verified-owned-container'}),patch.object(runtime,'network_identity'),patch.object(runtime,'cmd') as command:
            for operation in (runtime.start,runtime.stop):
                with self.assertRaisesRegex(RuntimeError,'database identity mismatch'):operation()
            command.assert_not_called()
    def test_foreign_network_is_rejected(self):
        with patch.object(runtime,'cmd',return_value='foreign-label') as command:
            with self.assertRaisesRegex(RuntimeError,'network ownership mismatch'):runtime.network_identity({'id':'own-runtime','network':'network-id'})
            self.assertEqual(command.call_count,1)
            self.assertEqual(command.call_args.args[0][:3],['docker','network','inspect'])
if __name__=='__main__':unittest.main()
