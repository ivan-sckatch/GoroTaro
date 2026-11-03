"""Editorial pipeline for major22. Never promotes generated drafts to approved."""
import argparse
import hashlib
import itertools
import json
import re
from difflib import SequenceMatcher
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POSITIONS = {'day1': ['theme'], 'week3': ['focus', 'obstacle', 'action'], 'decision3': ['a', 'b', 'check'], 'general3': ['past', 'present', 'future'], 'situation3': ['context', 'tension', 'support']}
def read(path): return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def key(spread, ids, orientations=None, orientation_mode='off'):
    orientations = orientations if orientations is not None else ['up'] * len(ids)
    if (len(ids) != len(POSITIONS[spread]) or len(set(ids)) != len(ids)
        or len(orientations) != len(ids) or any(o not in ('up', 'rev') for o in orientations)
        or orientation_mode not in ('off', 'on') or (orientation_mode == 'off' and 'rev' in orientations)):
        raise ValueError('Invalid cards or orientations')
    return f'v1|major22|{spread}|ru|{orientation_mode}|' + '|'.join(f'{p}:{c}:{o}' for p, c, o in zip(POSITIONS[spread], ids, orientations))
def keys(cards, spread, orientation_mode='off'):
    if orientation_mode not in ('off', 'on'): raise ValueError('Invalid orientation mode')
    for ids in itertools.permutations(sorted(c['cardId'] for c in cards), len(POSITIONS[spread])):
        for orientations in itertools.product(('up', 'rev') if orientation_mode == 'on' else ('up',), repeat=len(ids)):
            yield key(spread, ids, orientations, orientation_mode)
def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def validate(directory, production=False):
    directory = Path(directory)
    errors, warnings = [], []
    cards, spreads, manifest = read(directory/'cards.json'), read(directory/'spreads.json'), read(directory/'manifest.json')
    if any(manifest.get(k) != v for k, v in {'schemaVersion': 1, 'deckId': 'major22', 'locale': 'ru', 'mode': 'EXACT'}.items()) or manifest.get('orientationMode') not in ('on', 'off'): errors.append('Unsupported manifest schema')
    ids = [c['cardId'] for c in cards]
    if len(ids) != 22 or set(ids) != {f'major_{i:02d}' for i in range(22)}: errors.append('Deck must contain all 22 original cards')
    original = {c['cardId']: c for c in read(ROOT/'app/src/main/assets/cards.json')}
    for c in cards:
        if c['cardId'] not in original or any(c.get(k) != original[c['cardId']].get(k) for k in ('nameRu', 'imageAsset', 'aspectRatio')): errors.append(f"Card identity mismatch: {c['cardId']}")
        asset = (ROOT/'app/src/main/assets'/c.get('imageAsset', '')).resolve()
        if not asset.is_relative_to((ROOT/'app/src/main/assets').resolve()) or not asset.is_file(): errors.append(f"Missing or unsafe asset: {c['cardId']}")
    spread_ids = [s['spreadId'] for s in spreads]
    if not spread_ids or len(spread_ids) != len(set(spread_ids)): errors.append('Missing or duplicate spreads')
    for s in spreads:
        if s['spreadId'] not in POSITIONS or s.get('positions') != POSITIONS.get(s['spreadId']): errors.append('Invalid spread positions')
    expected = set(k for spread in spread_ids if spread in POSITIONS for k in keys(cards, spread, manifest.get('orientationMode') if manifest.get('orientationMode') in ('on', 'off') else 'off'))
    seen, texts, approved_keys = set(), set(), set()
    recent_summaries = []
    counts = {s: 0 for s in spread_ids}
    with (directory/'interpretations.jsonl').open(encoding='utf-8-sig') as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip(): continue
            try:
                record = json.loads(line); combo = record['comboKey']; variants = record['variants']; spread = combo.split('|')[2]
                if combo in seen: errors.append(f'Line {line_no}: duplicate key')
                seen.add(combo)
                if combo not in expected: errors.append(f'Line {line_no}: unknown or incorrectly ordered key')
                if spread in counts: counts[spread] += 1
                if not 2 <= len(variants) <= 3: errors.append(f'Line {line_no}: need 2–3 variants')
                variant_ids = [v['id'] for v in variants]
                if len(set(variant_ids)) != len(variant_ids) or any(not x.strip() for x in variant_ids): errors.append(f'Line {line_no}: invalid variant IDs')
                valid_approval = True
                for v in variants:
                    fields = [v['summary'], v['action'], v['reflection'], *v['positions'].values()]
                    if set(v['positions']) != set(POSITIONS.get(spread, [])): errors.append(f'Line {line_no}: incorrect position fields')
                    if any(not isinstance(t, str) or not t.strip() or len(t) > 4000 for t in fields): errors.append(f'Line {line_no}: invalid text length')
                    normalized = re.sub(r'\s+', ' ', ' '.join(fields).lower()).strip()
                    if normalized in texts: errors.append(f'Line {line_no}: duplicate complete text')
                    texts.add(normalized)
                    summary = re.sub(r'\s+', ' ', v['summary'].lower()).strip()
                    # A bounded editorial heuristic, never proof of semantic uniqueness.
                    for other_line, other_summary in recent_summaries[-8:]:
                        if SequenceMatcher(None, summary, other_summary, autojunk=False).ratio() >= .88:
                            warnings.append(f'Lines {other_line}/{line_no}: similar summaries; editorial review needed')
                            break
                    recent_summaries.append((line_no, summary))
                    if len(recent_summaries) > 8: recent_summaries.pop(0)
                    if v.get('status') != 'approved':
                        valid_approval = False
                        if production: errors.append(f'Line {line_no}: unapproved variant')
                if valid_approval: approved_keys.add(combo)
            except (KeyError, TypeError, IndexError, ValueError) as exc: errors.append(f'Line {line_no}: malformed record ({exc})')
    checksums = manifest.get('checksums', {})
    if set(checksums) != {'cards.json', 'spreads.json', 'interpretations.jsonl'}: errors.append('Missing checksum entries')
    for name, checksum in checksums.items():
        if name not in {'cards.json', 'spreads.json', 'interpretations.jsonl'} or sha(directory/name) != checksum: errors.append(f'Checksum mismatch: {name}')
    if production:
        if manifest.get('status') != 'approved' or manifest.get('approvedForProduction') is not True: errors.append('Manifest not approved')
        if expected != seen: errors.append('Incomplete EXACT coverage')
        if manifest.get('declaredCoverage') != len(expected): errors.append('Declared coverage mismatch')
    missing = sorted(expected-seen)
    return {'expected': len(expected), 'present': len(seen & expected), 'approved': len(approved_keys & expected), 'coveragePercent': round(100*len(seen & expected)/len(expected), 4) if expected else 0, 'perSpread': counts, 'errors': errors, 'warnings': warnings, 'missingExamples': missing[:5], 'productionReady': not errors and not missing and expected == approved_keys and manifest.get('approvedForProduction') is True}

def drafts(directory):
    directory = Path(directory); directory.mkdir(parents=True, exist_ok=True)
    cards = read(ROOT/'app/src/main/assets/cards.json')
    (directory/'cards.json').write_text(json.dumps(cards, ensure_ascii=False, indent=2), encoding='utf-8')
    (directory/'spreads.json').write_text(json.dumps([{'spreadId':'day1', 'positions':['theme']}], ensure_ascii=False, indent=2), encoding='utf-8')
    sample = read(ROOT/'reference/sample_content.json')['records'][-1]
    with (directory/'interpretations.jsonl').open('w', encoding='utf-8') as out:
        for c in cards:
            variants = [
                {'id':'v1', 'status':'draft', 'summary':f"{c['meaning']} Сегодня попробуйте соотнести эту тему с одной конкретной ситуацией, сохраняя внимание к доступным фактам.", 'positions':{'theme':f"Карта «{c['nameRu']}» предлагает посмотреть на день через эту тему: {c['meaning']}"}, 'action':c['action'], 'reflection':c['reflection']},
                {'id':'v2', 'status':'draft', 'summary':f"Посмотрите на сегодняшний день через образ карты «{c['nameRu']}». {c['meaning']} Выберите посильный шаг и оставьте себе время оценить его результат.", 'positions':{'theme':f"Не превращайте образ «{c['nameRu']}» в обязательный сценарий. {c['meaning']} Сравните эту мысль со своим опытом."}, 'action':f"Запишите один проверяемый шаг по теме «{c['nameRu']}» и условия, при которых вы готовы его сделать.", 'reflection':f"Что в теме карты «{c['nameRu']}» созвучно вашей ситуации, а что не подходит к ней?"}
            ]
            if c['cardId'] == 'major_01': variants = [dict(v, status='draft') for v in sample['variants']]
            out.write(json.dumps({'comboKey':key('day1', [c['cardId']]), 'variants':variants}, ensure_ascii=False)+'\n')
    manifest = {'schemaVersion':1, 'contentVersion':'day-draft-1', 'status':'draft', 'approvedForProduction':False, 'deckId':'major22', 'locale':'ru', 'orientationMode':'off', 'mode':'EXACT', 'declaredCoverage':22, 'checksums':{n:sha(directory/n) for n in ['cards.json','spreads.json','interpretations.jsonl']}}
    (directory/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')

def main():
    p = argparse.ArgumentParser(); sub = p.add_subparsers(dest='command', required=True)
    a = sub.add_parser('keys'); a.add_argument('--spread', choices=POSITIONS, required=True); a.add_argument('--out', required=True); a.add_argument('--orientation-mode', choices=['on', 'off'], default='on')
    a = sub.add_parser('drafts'); a.add_argument('directory')
    a = sub.add_parser('validate'); a.add_argument('directory'); a.add_argument('--production', action='store_true'); a.add_argument('--report')
    a = sub.add_parser('package'); a.add_argument('directory'); a.add_argument('--out', required=True)
    a = sub.add_parser('refresh-checksums'); a.add_argument('directory')
    args = p.parse_args()
    if args.command == 'keys':
        Path(args.out).write_text('\n'.join(keys(read(ROOT/'app/src/main/assets/cards.json'), args.spread, args.orientation_mode))+'\n', encoding='utf-8')
    elif args.command == 'drafts': drafts(args.directory)
    elif args.command == 'refresh-checksums':
        directory = Path(args.directory); manifest = read(directory/'manifest.json'); manifest['checksums'] = {n:sha(directory/n) for n in ['cards.json','spreads.json','interpretations.jsonl']}; (directory/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    else:
        report = validate(args.directory, args.command == 'package' or args.production)
        text = json.dumps(report, ensure_ascii=False, indent=2)
        if getattr(args, 'report', None): Path(args.report).write_text(text, encoding='utf-8')
        print(text)
        if report['errors']: raise SystemExit(1)
        if args.command == 'package':
            with zipfile.ZipFile(args.out, 'w', zipfile.ZIP_DEFLATED) as z:
                for name in ['manifest.json','cards.json','spreads.json','interpretations.jsonl']: z.write(Path(args.directory)/name, name)

if __name__ == '__main__': main()
