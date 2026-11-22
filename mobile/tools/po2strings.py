#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Buteo's translations: gettext catalogues to Android string resources.

The English texts are app/src/main/res/values/strings.xml.  A text that
XCSoar has too (the same English, no placeholders) takes XCSoar's
translation from po/<lang>.po; Buteo's own texts come from
mobile/po/<lang>.po (mobile/docs/DECISIONS.md D28).

  po2strings.py res STRINGS_XML OUT_DIR --po-dir po --app-po-dir mobile/po
      writes OUT_DIR/values-<lang>/strings.xml (the Gradle build runs it)
  po2strings.py pot STRINGS_XML --po-dir po --app-po-dir mobile/po
      writes mobile/po/buteo.pot (Buteo's own texts) and merges it into
      mobile/po/<lang>.po (needs gettext's msgmerge)

A <string po:msgctxt="..."> gives the gettext context.  A translation
whose placeholders differ from the English is left out (the English
shows): a wrong one would throw when the text is formatted.
"""

import argparse
import pathlib
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

PO_NS = 'urn:buteo:po'

# po file name -> Android resource qualifier
QUALIFIERS = {
    'pt_BR': 'pt-rBR',
    'zh_CN': 'zh-rCN',
    'zh_Hant': 'b+zh+Hant',
    'he': 'iw',
}

# Java format placeholders as Buteo writes them: positional, e.g. %1$s
POSITIONAL = re.compile(r'%(\d+)\$[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])')
# any other placeholder, e.g. %s or %.1f ("%%" is a literal percent)
OTHER = re.compile(r'%(?!\d+\$)[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]')


def android_unescape(raw):
    """The text an Android <string> shows (aapt2's rules, without markup)."""
    SPACE = '\0'  # unquoted whitespace: collapsed, trimmed at the ends
    out = []
    quoted = False
    i = 0
    while i < len(raw):
        c = raw[i]
        if c == '\\' and i + 1 < len(raw):
            n = raw[i + 1]
            if n == 'u':
                out.append(chr(int(raw[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({'n': '\n', 't': '\t'}.get(n, n))
            i += 2
            continue
        if c == '"':
            quoted = not quoted
        elif c.isspace() and not quoted:
            if not out or out[-1] != SPACE:
                out.append(SPACE)
        else:
            out.append(c)
        i += 1
    return ''.join(out).strip(SPACE).replace(SPACE, ' ')


def android_escape(text):
    s = (text.replace('\\', '\\\\').replace('"', '\\"').replace("'", "\\'")
         .replace('\n', '\\n').replace('\t', '\\t'))
    s = s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    if s.startswith(('@', '?')):
        s = '\\' + s
    if text != text.strip() or '  ' in text:
        s = '"' + s + '"'
    return s


def read_english(path):
    """[(name, context, text)] in the file's order."""
    root = ET.parse(path).getroot()
    strings = []
    for e in root:
        if e.tag != 'string' or e.get('translatable') == 'false':
            continue
        if len(e):
            sys.exit(f'{path}: {e.get("name")}: markup is not supported')
        strings.append((e.get('name'), e.get(f'{{{PO_NS}}}msgctxt'),
                        android_unescape(e.text or '')))
    return strings


def po_unquote(s):
    return re.sub(r'\\(.)',
                  lambda m: {'n': '\n', 't': '\t'}.get(m.group(1), m.group(1)),
                  s)


def read_catalogue(path):
    """The entries of a .po/.pot file: dicts with msgctxt, msgid, msgstr,
    msgid_plural, fuzzy; obsolete ones and the header left out."""
    entries = []
    text = pathlib.Path(path).read_text(encoding='utf-8')
    for block in re.split(r'\n\s*\n', text):
        entry = {'fuzzy': False}
        key = None
        for line in block.splitlines():
            line = line.strip()
            if line.startswith('#,') and 'fuzzy' in line:
                entry['fuzzy'] = True
            elif line.startswith('#'):
                continue
            elif line.startswith('"') and key:
                entry[key] += po_unquote(line[1:-1])
            else:
                m = re.match(r'(msgctxt|msgid_plural|msgid|msgstr(?:\[\d+\])?)'
                             r' "(.*)"$', line)
                if m:
                    key = m.group(1)
                    entry[key] = po_unquote(m.group(2))
        if entry.get('msgid'):
            entries.append(entry)
    return entries


def read_po(path):
    """{(context, msgid): msgstr}, translated and not fuzzy."""
    return {(e.get('msgctxt'), e['msgid']): e['msgstr']
            for e in read_catalogue(path)
            if e.get('msgstr') and not e['fuzzy'] and 'msgid_plural' not in e}


def placeholders(text):
    return sorted(POSITIONAL.findall(text))


def same_placeholders(english, translation):
    """The same %1$s placeholders and, in a text that gets formatted, no
    other one ("50 % de" is fine in a text that never is)."""
    if placeholders(english) != placeholders(translation):
        return False
    return not placeholders(english) or \
        not OTHER.search(translation.replace('%%', ''))


def from_xcsoar(context, text):
    """Whether XCSoar's catalogue is asked for this text."""
    return not POSITIONAL.search(text)


def languages(po_dir, app_po_dir):
    names = {p.stem for p in pathlib.Path(po_dir).glob('*.po')}
    names |= {p.stem for p in pathlib.Path(app_po_dir).glob('*.po')}
    return sorted(names)


def write_resources(args):
    english = read_english(args.strings)
    out = pathlib.Path(args.out_dir)
    for lang in languages(args.po_dir, args.app_po_dir):
        xcsoar = pathlib.Path(args.po_dir, f'{lang}.po')
        app = pathlib.Path(args.app_po_dir, f'{lang}.po')
        xcsoar = read_po(xcsoar) if xcsoar.exists() else {}
        app = read_po(app) if app.exists() else {}
        lines = []
        for name, context, text in english:
            key = (context, text)
            translation = app.get(key)
            if translation is None and from_xcsoar(context, text):
                translation = xcsoar.get(key)
            if translation is None:
                continue
            if not same_placeholders(text, translation):
                print(f'po2strings: {lang}: {name}: placeholders differ, '
                      'left out', file=sys.stderr)
                continue
            lines.append(f'    <string name="{name}">'
                         f'{android_escape(translation)}</string>')
        if not lines:
            continue
        d = out / f'values-{QUALIFIERS.get(lang, lang)}'
        d.mkdir(parents=True, exist_ok=True)
        (d / 'strings.xml').write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- generated by mobile/tools/po2strings.py from po/{lang}.po'
            f' and mobile/po/{lang}.po -->\n'
            '<resources>\n' + '\n'.join(lines) + '\n</resources>\n',
            encoding='utf-8')


def po_quote(s):
    return '"' + (s.replace('\\', '\\\\').replace('"', '\\"')
                  .replace('\n', '\\n').replace('\t', '\\t')) + '"'


def write_pot(args):
    xcsoar = {(e.get('msgctxt'), e['msgid']) for e in
              read_catalogue(pathlib.Path(args.po_dir, 'xcsoar.pot'))}
    seen = set()
    parts = ['# Buteo\'s own texts: those XCSoar has too are translated in\n'
             '# po/ (mobile/docs/DECISIONS.md D28).\n'
             'msgid ""\nmsgstr ""\n'
             '"Project-Id-Version: Buteo\\n"\n'
             '"MIME-Version: 1.0\\n"\n'
             '"Content-Type: text/plain; charset=UTF-8\\n"\n'
             '"Content-Transfer-Encoding: 8bit\\n"\n']
    for name, context, text in read_english(args.strings):
        key = (context, text)
        if (from_xcsoar(context, text) and key in xcsoar) or key in seen:
            continue
        seen.add(key)
        entry = f'#. {name}\n'
        if context is not None:
            entry += f'msgctxt {po_quote(context)}\n'
        entry += f'msgid {po_quote(text)}\nmsgstr ""\n'
        parts.append(entry)
    pot = pathlib.Path(args.app_po_dir, 'buteo.pot')
    pot.write_text('\n'.join(parts), encoding='utf-8')
    for po in sorted(pathlib.Path(args.app_po_dir).glob('*.po')):
        with tempfile.NamedTemporaryFile(suffix='.po') as tmp:
            subprocess.run(['msgmerge', '--quiet', '--no-wrap', '--no-location',
                            '--no-fuzzy-matching', '-o', tmp.name, str(po),
                            str(pot)], check=True)
            subprocess.run(['msgattrib', '--no-wrap', '--no-obsolete',
                            '-o', str(po), tmp.name], check=True)


def main():
    p = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    p.add_argument('command', choices=['res', 'pot'])
    p.add_argument('strings', help='res/values/strings.xml')
    p.add_argument('out_dir', nargs='?', help='res: the generated res dir')
    p.add_argument('--po-dir', required=True, help="XCSoar's po/")
    p.add_argument('--app-po-dir', required=True, help="Buteo's mobile/po/")
    args = p.parse_args()
    if args.command == 'res':
        if not args.out_dir:
            p.error('res needs OUT_DIR')
        write_resources(args)
    else:
        write_pot(args)


if __name__ == '__main__':
    main()
