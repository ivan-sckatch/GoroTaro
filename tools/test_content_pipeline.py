import copy
import json
import tempfile
import unittest
from pathlib import Path
import content_pipeline as cp

class PipelineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.directory = Path(self.temp.name); cp.drafts(self.directory)
    def tearDown(self): self.temp.cleanup()
    def test_drafts_cover_day_but_are_never_production_ready(self):
        report = cp.validate(self.directory)
        self.assertEqual(report['present'], 22); self.assertEqual(report['approved'], 0); self.assertEqual(report['errors'], [])
        self.assertFalse(report['productionReady']); self.assertTrue(cp.validate(self.directory, True)['errors'])
    def test_missing_card_and_corrupt_checksums_fail(self):
        cards = cp.read(self.directory/'cards.json'); cards.pop(); (self.directory/'cards.json').write_text(json.dumps(cards), encoding='utf-8')
        errors = cp.validate(self.directory)['errors']; self.assertTrue(any('22 original' in e for e in errors)); self.assertTrue(any('Checksum' in e for e in errors))
    def test_duplicate_complete_text_fails(self):
        path = self.directory/'interpretations.jsonl'; records = [json.loads(l) for l in path.read_text(encoding='utf-8').splitlines()]
        records[0]['variants'][1] = dict(records[0]['variants'][0], id='v2')
        path.write_text('\n'.join(json.dumps(r, ensure_ascii=False) for r in records), encoding='utf-8')
        self.assertTrue(any('duplicate complete' in e for e in cp.validate(self.directory)['errors']))
    def test_reversed_keys_and_general_positions(self):
        cards = cp.read(self.directory/'cards.json')
        self.assertEqual(len(list(cp.keys(cards, 'day1', 'on'))), 44)
        keys = list(cp.keys(cards, 'general3', 'on'))
        self.assertEqual(len(keys), 73920); self.assertEqual(len(set(keys)), 73920)
        self.assertIn('past:major_00:rev|present:major_01:up|future:major_02:rev', cp.key('general3', ['major_00','major_01','major_02'], ['rev','up','rev'], 'on'))
        with self.assertRaises(ValueError): cp.key('day1', ['major_00'], ['rev'], 'off')
        with self.assertRaises(ValueError): cp.key('day1', ['major_00'], [], 'on')
    def test_all_44_meanings_are_sourced_and_asset_generation_preserves_them(self):
        catalog = cp.read(cp.ROOT/'content/major22-meanings.json')
        bundled = cp.read(cp.ROOT/'app/src/main/assets/cards.json')
        self.assertEqual(catalog['cards'], bundled); self.assertEqual(len(bundled), 22)
        for card in bundled:
            for field in ('meaning','action','reflection','reversedMeaning','reversedAction','reversedReflection'):
                self.assertTrue(card[field].strip(), (card['cardId'],field))
            self.assertNotEqual(card['meaning'], card['reversedMeaning'])
            self.assertTrue(card['sources'][0].startswith('https://labyrinthos.co/'))
        self.assertEqual(len({c['sources'][0] for c in bundled}), 22)
    def test_narrative_bank_has_three_complete_variants_per_orientation(self):
        cards = cp.read(cp.ROOT/'app/src/main/assets/cards.json')
        total = 0
        for card in cards:
            for orientation, field in (('up','narrative'), ('rev','reversedNarrative')):
                entry = card[field]
                self.assertEqual(len(entry['variants']),3)
                self.assertEqual(len(set(entry['variants'])),3)
                self.assertIn(entry['pace'], ('move','pause','structure','connect','change'))
                self.assertTrue(entry['goal'].strip()); self.assertTrue(entry['risk'].strip())
                for text in entry['variants']:
                    self.assertTrue(text.endswith('.'), (card['cardId'],orientation,text))
                    self.assertNotIn('{',text)
                    self.assertNotIn('посмотрите через',text.lower())
                total += len(entry['variants'])
        self.assertEqual(total,132)
    def test_on_manifest_requires_reversed_records(self):
        path = self.directory/'manifest.json'; manifest = cp.read(path); manifest['orientationMode'] = 'on'; manifest['declaredCoverage'] = 44
        path.write_text(json.dumps(manifest), encoding='utf-8')
        report = cp.validate(self.directory, True)
        self.assertEqual(report['expected'],44); self.assertEqual(report['present'],0)
        self.assertTrue(any('unknown' in e for e in report['errors']))
    def test_ordered_three_card_keys(self):
        cards = cp.read(self.directory/'cards.json'); keys = list(cp.keys(cards, 'week3'))
        self.assertEqual(len(keys), 9240); self.assertEqual(len(set(keys)), 9240)
        self.assertNotEqual(cp.key('week3', ['major_00','major_01','major_02']), cp.key('week3', ['major_01','major_00','major_02']))

if __name__ == '__main__': unittest.main()
