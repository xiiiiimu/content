"""Transport contract only; real Redis reconciliation is exercised by the live run."""
import json,subprocess,unittest
from types import SimpleNamespace
from unittest.mock import patch
from flash_acceptance import redis_snapshot

class RedisSnapshotTransportTest(unittest.TestCase):
    def test_5000_ids_use_stdin_instead_of_windows_command_line(self):
        rows=[[f'{i:032x}'] for i in range(5000)]
        with patch('flash_acceptance.subprocess.run',return_value=SimpleNamespace(stdout='{"version":1,"stock":0,"processing":0}')) as call:
            redis_snapshot(1,rows)
        argv=call.call_args.args[0]
        self.assertLess(len(subprocess.list2cmdline(argv)),5000)
        self.assertIn('-i',argv);self.assertIn('-x',argv)
        self.assertEqual(','.join(r[0] for r in rows),call.call_args.kwargs['input'])

    def test_empty_set_is_transmitted_without_extra_reservations(self):
        with patch('flash_acceptance.subprocess.run',return_value=SimpleNamespace(stdout='{"version":1,"stock":1,"processing":0}')) as call:
            result=redis_snapshot(1,[])
        self.assertEqual('',call.call_args.kwargs.get('input'))
        self.assertEqual(0,result['processing'])

if __name__=='__main__':unittest.main()
