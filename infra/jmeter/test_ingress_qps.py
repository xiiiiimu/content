import base64, json, unittest
import run_ingress_qps as q
import env_sampler as s


def summary(reqs, p99, sold=None, extra=None, dropped=0):
    m = {'http_reqs': {'values': {'count': reqs}}, 'http_req_duration': {'values': {'med': 3, 'p(95)': 10, 'p(99)': p99, 'max': 400, 'avg': 4}},
         'biz_sold_out': {'values': {'count': reqs if sold is None else sold}}}
    if dropped: m['dropped_iterations'] = {'values': {'count': dropped}}
    for k, v in (extra or {}).items(): m[k] = {'values': {'count': v}}
    return {'metrics': m}


class IngressQpsTest(unittest.TestCase):
    def test_vus_rate_times_rt_times_three_with_floor_and_cap(self):
        self.assertEqual(30, q.vus_for(200, 50)); self.assertEqual(450, q.vus_for(3000, 50))
        self.assertEqual(10, q.vus_for(10, 50)); self.assertEqual(600, q.vus_for(10000, 50))

    def test_affinity_mask_to_cpus(self):
        self.assertEqual([10, 11, 12, 13, 14, 15], q.mask_cpus(0xFC00)); self.assertEqual(list(range(10)), q.mask_cpus(0x3FF))

    def test_token_expiry_from_payload(self):
        head = base64.urlsafe_b64encode(json.dumps({'expiresAt': 123}).encode()).decode().rstrip('=')
        self.assertEqual(123, q.token_expiry_ms(head + '.sig'))

    def test_level_rates_and_categories(self):
        lv = q.k6_level(summary(5700, 20, sold=5690, extra={'err_timeout': 4, 'err_conn': 1, 'rej_401': 5}, dropped=300), 200, 30)
        self.assertEqual(190.0, lv['actualRate']); self.assertEqual(95.0, lv['actualPct']); self.assertEqual(300, lv['droppedIterations'])
        self.assertEqual({'accepted': 0, 'soldOut': 5690, 'otherReject': 5, 'http503': 0, 'http5xxOther': 0, 'timeout': 4, 'connFail': 1}, lv['counts'])
        self.assertEqual({'rej_401': 5}, lv['otherRejectDetail'])

    def test_clean_level(self):
        lv = q.k6_level(summary(6000, 50), 200, 30); v = q.judge(lv, 1.0, 6)
        self.assertTrue(v['valid']); self.assertTrue(v['clean']); self.assertEqual(0, v['errors'])

    def test_knee_conditions(self):
        self.assertTrue(q.judge(q.k6_level(summary(6000, 201), 200, 30), 1.0, 6)['knee']['p99Over200ms'])
        v = q.judge(q.k6_level(summary(6000, 50, sold=5993, extra={'err_5xx': 7}), 200, 30), 1.0, 6)
        self.assertTrue(v['knee']['errorRateOver0.1pct']); self.assertFalse(v['clean'])
        v = q.judge(q.k6_level(summary(6000, 50, sold=5994, extra={'err_5xx': 6}), 200, 30), 1.0, 6)
        self.assertFalse(v['knee']['errorRateOver0.1pct'])  # exactly 0.1 % is not over
        v = q.judge(q.k6_level(summary(5600, 50), 200, 30), 1.0, 6)
        self.assertTrue(v['valid']); self.assertTrue(v['knee']['arrivalUnder95pct'])  # server held VUs: a knee, still valid

    def test_injector_saturated_or_unmeasured_is_invalid(self):
        v = q.judge(q.k6_level(summary(5000, 50), 200, 30), 5.5, 6)
        self.assertFalse(v['valid']); self.assertEqual('发压机受限', v['label'])
        self.assertFalse(q.judge(q.k6_level(summary(6000, 50), 200, 30), None, 6)['valid'])

    def test_unexpected_accept_is_an_error_on_soldout_path(self):
        v = q.judge(q.k6_level(summary(6000, 50, sold=5990, extra={'biz_accepted': 10}), 200, 30), 1.0, 6)
        self.assertEqual(10, v['errors'])

    def test_sustainable_skips_invalid_and_stops_at_first_knee(self):
        L = lambda r, valid, clean: {'targetRate': r, 'verdict': {'valid': valid, 'clean': clean}}
        self.assertEqual(1000, q.sustainable([L(200, True, True), L(500, True, True), L(1000, True, True), L(1500, True, False), L(2000, True, True)]))
        self.assertEqual(500, q.sustainable([L(200, True, True), L(500, True, True), L(1000, False, False), L(1500, True, False)]))
        self.assertIsNone(q.sustainable([L(200, True, False)]))

    def test_access_log_window_and_microsecond_unit(self):
        lines = ['1000,1005,4800,409,/api/sale-campaigns/7/purchase,-,x', '999,1001,100,409,/api/sale-campaigns/7/purchase,-,x',
                 '1001,1002,900,200,/actuator/health,-,x', 'garbage', '2000,2001,1000,409,/api/sale-campaigns/7/purchase,-,x']
        r = q.parse_access(lines, 1000, 2000)
        self.assertEqual(1, r['appMs']['n']); self.assertEqual(4.8, r['appMs']['max']); self.assertEqual(5, r['beginEndMs']['max'])
        self.assertEqual({'409': 1}, r['statuses'])

    def test_dist_nearest_rank(self):
        d = q.dist(list(range(1, 101)))
        self.assertEqual((50, 95, 99, 100), (d['p50'], d['p95'], d['p99'], d['max']))

    def test_vu_cap_is_a_parameter(self):
        self.assertEqual(3000, q.vus_for(5000, 200, cap=4000)); self.assertEqual(2100, q.vus_for(3500, 200, cap=4000))

    def test_peak_in_flight_end_before_begin_at_same_ms(self):
        self.assertEqual(2, q.peak_in_flight([(0, 10), (5, 6), (10, 12)]))
        self.assertEqual(0, q.peak_in_flight([]))
        self.assertEqual(1, q.parse_access(['1000,1005,4800,409,/api/sale-campaigns/7/purchase,-,x'], 1000, 2000)['peakInFlight'])

    def test_drop_attribution(self):
        L = lambda p99, dropped, sat=False: {'httpMs': {'p99': p99}, 'droppedIterations': dropped, 'verdict': {'injectorSaturated': sat}}
        lv = q.attribute_drops([L(10, 0), L(8, 0), L(45, 312), L(15, 5), L(90, 50, True)])
        self.assertNotIn('dropReason', lv[0]['verdict'])
        self.assertEqual('serverOrHostSaturation', lv[2]['verdict']['dropReason']); self.assertEqual(8, lv[2]['verdict']['baselineP99'])
        self.assertEqual('unattributed', lv[3]['verdict']['dropReason']); self.assertEqual('injector', lv[4]['verdict']['dropReason'])

    def test_campaigns_needed_never_reuses_pairs(self):
        self.assertEqual(1, q.campaigns_needed(100, 20, 4000)); self.assertEqual(2, q.campaigns_needed(200, 20, 4000))
        self.assertEqual(3, q.campaigns_needed(400, 20, 4000)); self.assertEqual(1, q.campaigns_needed(50, 10, 4000))

    def test_pair_overflow_invalidates_level(self):
        lv = q.k6_level(summary(6000, 50, sold=0, extra={'biz_accepted': 6000, 'pair_overflow': 3}), 200, 30)
        v = q.judge(lv, 1.0, 6, ('accepted',)); self.assertFalse(v['valid']); self.assertEqual(3, lv['pairOverflow'])

    def test_parse_records_from_console_output(self):
        lines = ['time="x" level=info msg="R,7,101,55,200,0,,abc-1,3.20" source=console', 'noise',
                 'time="x" level=info msg="R,8,102,55,0,1050,,,5000.00" source=console', 'R,9,101,56,409,1409,SOLD_OUT,,2.00']
        r = q.parse_records(lines)
        self.assertEqual([7, 8, 9], [x['i'] for x in r]); self.assertEqual('abc-1', r[0]['reservationId'])
        self.assertEqual((0, 1050), (r[1]['status'], r[1]['errorCode'])); self.assertEqual('SOLD_OUT', r[2]['code'])

    def test_drain_pending(self):
        ledger = {'requests': [['a', 1, 'k', 'PROCESSING'], ['b', 1, 'k', 'SUCCEEDED']], 'orders': [1, 1, 0, 0]}
        d = q.drain_pending(ledger, {'processing': 0, 'streamPending': 2, 'streamLag': None, 'dispatchPending': 0, 'cacheJobsPending': 0})
        self.assertEqual({'processing': 1, 'heldOrders': 0, 'redisProcessing': 0, 'streamPending': 2, 'streamLag': 0, 'dispatchPending': 0, 'cacheJobsPending': 0}, d)


def rec(i, c, u, status, rid='', ec=0):
    return {'i': i, 'campaign': c, 'userId': u, 'status': status, 'errorCode': ec, 'code': '', 'reservationId': rid, 'ms': 1.0}


def row(rid, u, key, state='SUCCEEDED', order='PAID', ent=1):
    return [rid, str(u), key, state, '9', order, '1', str(ent), 'APPLIED']


class ReconcileTest(unittest.TestCase):
    def setUp(self):
        self.records = [rec(0, 10, 1, 200, 'r0'), rec(1, 11, 1, 200, 'r1'), rec(2, 10, 2, 0, ec=1050)]
        self.ledgers = {10: {'stockCap': 100, 'sold': 1, 'remaining': 99, 'requests': [row('r0', 1, 't-0')], 'entitlements': [1, 0], 'orders': [1, 1, 0, 0]},
                        11: {'stockCap': 100, 'sold': 1, 'remaining': 99, 'requests': [row('r1', 1, 't-1')], 'entitlements': [1, 0], 'orders': [1, 1, 0, 0]}}
        self.caches = {10: {'stock': 99, 'processing': 0, 'invalidRequests': 0}, 11: {'stock': 99, 'processing': 0, 'invalidRequests': 0}}

    def test_clean_reconciliation(self):
        r = q.reconcile(self.records, self.ledgers, self.caches, 't', 3)
        self.assertTrue(r['pass'], r['mismatches']); self.assertEqual(2, r['matched'])

    def test_missing_row_phantom_and_stock_drift_fail(self):
        self.ledgers[11]['requests'] = []
        self.ledgers[10]['requests'].append(row('zz', 9, 'other-key')); self.caches[10]['stock'] = 98
        r = q.reconcile(self.records, self.ledgers, self.caches, 't', 3)
        self.assertFalse(r['pass']); m = r['mismatches']
        self.assertEqual(1, m['acceptedWithoutRow']); self.assertEqual(1, m['phantomRow']); self.assertEqual(1, m['redisStock'])

    def test_timeout_persisted_is_ambiguous_not_phantom(self):
        self.ledgers[10]['requests'].append(row('r2', 2, 't-2')); self.ledgers[10]['sold'] = 2; self.ledgers[10]['orders'] = [2, 2, 0, 0]
        self.ledgers[10]['entitlements'] = [2, 0]; self.caches[10]['stock'] = 98
        r = q.reconcile(self.records, self.ledgers, self.caches, 't', 3)
        self.assertTrue(r['pass'], r['mismatches']); self.assertEqual(1, r['ambiguousNoResponseButPersisted'])

    def test_wrong_state_identity_and_count(self):
        self.ledgers[10]['requests'] = [row('r0', 1, 't-0', state='PROCESSING')]; self.ledgers[11]['requests'] = [row('r1', 5, 't-1')]
        r = q.reconcile(self.records, self.ledgers, self.caches, 't', 4)
        self.assertEqual(1, r['mismatches']['notSucceeded:PROCESSING']); self.assertEqual(1, r['mismatches']['identityMismatch'])
        self.assertEqual(1, r['mismatches']['recordCountVsK6'])

    def test_repeated_pair_is_flagged(self):
        r = q.reconcile(self.records + [rec(3, 10, 1, 409)], self.ledgers, self.caches, 't', 4)
        self.assertEqual(1, r['mismatches']['repeatedBuyerMaterialPair'])


class SamplerParsingTest(unittest.TestCase):
    def test_group_stats_and_info_field(self):
        groups = [['name', 'other', 'lag', 5, 'pending', 1], ['name', 'sale-persist-v2', 'consumers', 3, 'pending', 2, 'lag', 7]]
        self.assertEqual({'lag': 7, 'pending': 2}, s.group_stats(groups)); self.assertIsNone(s.group_stats([]))
        self.assertEqual(1234, s.info_field('# Memory\r\nused_memory:1234\r\nused_memory_human:1K\r\n', 'used_memory'))

    def test_commandstats_scripts_only(self):
        info = 'cmdstat_evalsha:calls=10,usec=500,usec_per_call=50\r\ncmdstat_hget:calls=3,usec=9\r\ncmdstat_eval:calls=2,usec=40'
        self.assertEqual({'evalsha': (10, 500), 'eval': (2, 40)}, s.commandstats(info))


if __name__ == '__main__':
    unittest.main()
